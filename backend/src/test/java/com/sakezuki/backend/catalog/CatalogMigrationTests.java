package com.sakezuki.backend.catalog;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
class CatalogMigrationTests {
    private static final List<String> CATALOG = List.of("SAKE", "BREWERY", "BRAND");
    private static final List<String> PRESERVED = List.of("SAKE", "BREWERY", "BRAND", "SAKE_RECOMMEND_PROFILE",
            "RECOMMEND_FOOD", "RECOMMEND_SAKE", "RECOMMEND_REQUEST");
    private DriverManagerDataSource ds;
    private JdbcTemplate db;

    @BeforeEach
    void resetDisposableSchema() {
        String authUrl = System.getenv("AUTH_TEST_JDBC_URL");
        assertNotNull(authUrl);
        assertTrue(authUrl.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?"));
        ds = new DriverManagerDataSource(authUrl.replace("/sakezuki_auth_it", "/sakezuki_catalog_it"),
                System.getenv("AUTH_TEST_DB_USER"), System.getenv("AUTH_TEST_DB_PASSWORD"));
        db = new JdbcTemplate(ds);
        flyway("8").clean();
    }

    @Test
    void freshDatabaseMigratesAndBackfillsEverySeedIdentity() {
        assertEquals(8, flyway("8").migrate().migrationsExecuted);
        assertCompleteBackfill();
    }

    @Test
    void upgradesV5WithoutChangingExistingRowsOrKeys() {
        assertEquals(5, flyway("5").migrate().migrationsExecuted);
        db.update("INSERT INTO RECOMMEND_FOOD(sake_no,name,reason) VALUES(1,'fixture food','fixture')");
        db.update("INSERT INTO RECOMMEND_SAKE(food,sake_no,reason) VALUES('fixture food',1,'fixture')");
        Map<String, List<Map<String, Object>>> before = PRESERVED.stream().collect(
                Collectors.toMap(table -> table, table -> db.queryForList("SELECT * FROM " + table + " ORDER BY 1")));
        assertEquals(3, flyway("8").migrate().migrationsExecuted);
        assertCompleteBackfill();
        before.forEach((table, rows) -> assertEquals(rows, db.queryForList("SELECT * FROM " + table + " ORDER BY 1"), table));
    }

    @Test
    void conflictingBackfillStopsBeforeNullableTransitionAndCanBeRepaired() throws Exception {
        flyway("6").migrate();
        try (var physical = ds.getConnection()) {
            var reused = new SingleConnectionDataSource(physical, true);
            db = new JdbcTemplate(reused);
            var retry = Flyway.configure().dataSource(reused).target("8").load();
            Long session = db.queryForObject("SELECT CONNECTION_ID()", Long.class);
            db.update("""
                    INSERT INTO SAKE_SOURCE (sake_no, source_system, external_key)
                    SELECT (SELECT MAX(no) FROM SAKE), 'SAKE_GUIDE', CAST(source_id AS CHAR)
                    FROM SAKE ORDER BY no LIMIT 1
                    """);
            assertThrows(FlywayException.class, () -> retry.migrate());
            assertEquals("NO", db.queryForObject("""
                    SELECT IS_NULLABLE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='SAKE' AND COLUMN_NAME='source_id'
                    """, String.class));
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM `flyway_schema_history` WHERE version='7' AND success=0", Long.class));
            assertEquals(1, count("SAKE_SOURCE")); // V7 DML rolled back; V6 DDL survived.
            db.update("""
                    DELETE s FROM SAKE_SOURCE s JOIN SAKE c ON c.no=s.sake_no
                    WHERE s.source_system='SAKE_GUIDE' AND s.external_key <> CAST(c.source_id AS CHAR)
                    """);
            assertEquals(session, db.queryForObject("SELECT CONNECTION_ID()", Long.class));
            retry.repair();
            assertEquals(2, retry.migrate().migrationsExecuted);
            assertEquals(session, db.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertCompleteBackfill();
        }
    }

    private void assertCompleteBackfill() {
        assertEquals(10_337, count("SAKE"));
        for (String table : CATALOG) {
            String fk = table.toLowerCase(java.util.Locale.ROOT) + "_no";
            assertEquals("YES", db.queryForObject("""
                    SELECT IS_NULLABLE FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='source_id'
                    """, String.class, table));
            assertEquals(count(table), count(table + "_SOURCE"));
            assertEquals(count(table), db.queryForObject("""
                    SELECT COUNT(*) FROM %1$s c JOIN %1$s_SOURCE s ON c.no=s.%2$s
                    WHERE s.source_system='SAKE_GUIDE' AND s.external_key=CAST(c.source_id AS CHAR CHARACTER SET utf8mb4)
                    """.formatted(table, fk), Long.class));
        }
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).cleanDisabled(false).target(target).load();
    }
}
