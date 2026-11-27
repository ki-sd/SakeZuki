package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandData;
import com.sakezuki.tools.model.BreweryData;
import com.sakezuki.tools.model.SakeData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
class GuideCatalogMySqlTests {
    private static final long GUIDE_ID = 7_000_000_001L;

    @Test
    void recrawlRetainsInternalIdsAndOriginalGuideKeySpelling() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                var breweries = new BreweryRepository(connection);
                var brands = new BrandRepository(connection);
                var sakes = new SakeRepository(connection);
                var brewery = BreweryData.builder().sourceId("CatalogTestBrewery").nameJa("合成蔵").build();
                var brand = BrandData.builder().sourceId("CatalogTestBrand").nameJa("合成銘柄").build();
                var sake = SakeData.builder().sourceId(GUIDE_ID).nameJa("合成酒").build();
                long breweryNo = breweries.save(brewery);
                long brandNo = brands.save(brand, breweryNo);
                long sakeNo = sakes.save(sake, breweryNo, brandNo);

                brewery.setSourceId("catalogtestbrewery");
                brand.setSourceId("catalogtestbrand");
                sake.setNameJa("合成酒更新");
                assertEquals(breweryNo, breweries.save(brewery));
                assertEquals(brandNo, brands.save(brand, breweryNo));
                assertEquals(sakeNo, sakes.save(sake, breweryNo, brandNo));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BREWERY_SOURCE WHERE external_key='CatalogTestBrewery'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND_SOURCE WHERE external_key='CatalogTestBrand'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE_SOURCE WHERE external_key='7000000001'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM SAKE WHERE source_id=7000000001 AND name_ja='合成酒更新'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sourceLessRowsAreExcludedFromGuideImageTranslationAndCleanup() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('UserFixtureBrewery')");
                execute(connection, """
                        INSERT INTO BRAND(brewery_no,name_ja)
                        SELECT no,'UserFixtureBrand' FROM BREWERY WHERE name_ja='UserFixtureBrewery'
                        """);
                execute(connection, """
                        INSERT INTO SAKE(brewery_no,brand_no,name_ja,rice,yeast)
                        SELECT brewery_no,no,'SameNameCatalogFixture','CatalogFixtureRice','CatalogFixtureYeast'
                        FROM BRAND WHERE name_ja='UserFixtureBrand'
                        """);
                long userNo = scalar(connection, "SELECT no FROM SAKE WHERE name_ja='SameNameCatalogFixture'");
                execute(connection, "INSERT INTO SAKE_SOURCE(sake_no, source_system) VALUES(" + userNo + ",'USER_SUBMISSION')");
                long guideBreweryNo = new BreweryRepository(connection).save(
                        BreweryData.builder().sourceId("CatalogTestBrewery").nameJa("GuideFixtureBrewery").build());
                long guideBrandNo = new BrandRepository(connection).save(
                        BrandData.builder().sourceId("CatalogTestBrand").nameJa("GuideFixtureBrand").build(), guideBreweryNo);
                var sakes = new SakeRepository(connection);
                long guideNo = sakes.save(SakeData.builder().sourceId(GUIDE_ID).nameJa("SameNameCatalogFixture")
                        .rice("CatalogFixtureRice").yeast("CatalogFixtureYeast").build(), guideBreweryNo, guideBrandNo);
                var translations = new TranslationRepository(connection);
                assertTrue(sakes.findUncheckedImageSourceIds().contains(GUIDE_ID));
                assertFalse(sakes.findUncheckedImageSourceIds().contains(0L));
                sakes.updateImageUrl(GUIDE_ID, "https://example.invalid/test.png");
                assertFalse(sakes.findUncheckedImageSourceIds().contains(GUIDE_ID));
                assertFalse(translations.findUntranslatedBrands(Integer.MAX_VALUE).stream()
                        .anyMatch(item -> item.getNameJa().equals("UserFixtureBrand")));
                assertTrue(translations.findUntranslatedSakeNames(Integer.MAX_VALUE).stream()
                        .anyMatch(item -> item.getNameJa().equals("SameNameCatalogFixture") && item.getNo() == guideNo));
                translations.updateSakeNameKo("SameNameCatalogFixture", null, "합성");
                translations.updateRiceKo("CatalogFixtureRice", "합성");
                translations.updateYeastKo("CatalogFixtureYeast", "합성");
                translations.replaceRiceValue("CatalogFixtureRice", "GuideRice");
                translations.replaceYeastValue("CatalogFixtureYeast", "GuideYeast");
                assertEquals(1, scalar(connection, """
                        SELECT COUNT(*) FROM SAKE WHERE no=%d AND name_ko IS NULL
                        AND rice_ko IS NULL AND yeast_ko IS NULL AND rice='CatalogFixtureRice'
                        AND yeast='CatalogFixtureYeast' AND image_url IS NULL AND image_checked='N'
                        """.formatted(userNo)));
                assertEquals(1, scalar(connection, """
                        SELECT COUNT(*) FROM SAKE WHERE no=%d AND name_ko='합성'
                        AND rice_ko='합성' AND yeast_ko='합성' AND rice='GuideRice'
                        AND yeast='GuideYeast' AND image_checked='Y'
                        """.formatted(guideNo)));
                long userBrand = scalar(connection, "SELECT no FROM BRAND WHERE name_ja='UserFixtureBrand'");
                translations.updateBrandNameKo(userBrand, "합성");
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM BRAND WHERE no=" + userBrand + " AND name_ko IS NULL"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sourceConstraintsAndConflictingDualWriteRejectWrongOwner() throws Exception {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO BREWERY(name_ja) VALUES('UserFixtureA'),('UserFixtureB')");
                long first = scalar(connection, "SELECT no FROM BREWERY WHERE name_ja='UserFixtureA'");
                long second = scalar(connection, "SELECT no FROM BREWERY WHERE name_ja='UserFixtureB'");
                String prefix = "INSERT INTO BREWERY_SOURCE(brewery_no, source_system, external_key) VALUES";
                execute(connection, prefix + "(" + first + ",'SAKE_GUIDE','ReservedFixture')");
                execute(connection, prefix + "(" + second + ",'BREWERY_OFFICIAL','ReservedFixture')");
                execute(connection, prefix + "(" + first + ",'USER_SUBMISSION',NULL)");
                execute(connection, prefix + "(" + second + ",'USER_SUBMISSION',NULL)");
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(" + first + ",'USER_SUBMISSION',NULL)"));
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(" + second + ",'SAKE_GUIDE','ReservedFixture')"));
                assertThrows(SQLException.class, () -> execute(connection, prefix + "(0,'ADMIN',NULL)"));
                var conflicting = BreweryData.builder().sourceId("ReservedFixture").nameJa("WrongOwner").build();
                assertThrows(SQLException.class, () -> new BreweryRepository(connection).save(conflicting));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM BREWERY WHERE source_id='ReservedFixture'"));
                assertEquals(first, scalar(connection, "SELECT brewery_no FROM BREWERY_SOURCE WHERE source_system='SAKE_GUIDE' AND external_key='ReservedFixture'"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void recrawlSeesGuideMappingCommittedAfterRepeatableReadSnapshot() throws Exception {
        try (var older = connect(); var writer = connect()) {
            older.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            older.setAutoCommit(false);
            long breweryNo = 0;
            try {
                assertEquals(0, scalar(older, "SELECT COUNT(*) FROM BREWERY_SOURCE WHERE external_key='RaceFixture'"));
                var brewery = BreweryData.builder().sourceId("RaceFixture").nameJa("合成競合蔵").build();
                breweryNo = new BreweryRepository(writer).save(brewery);
                assertTrue(writer.getAutoCommit());
                assertEquals(breweryNo, new BreweryRepository(older).save(brewery));
            } finally {
                older.rollback();
                if (breweryNo != 0) {
                    execute(writer, "DELETE FROM BREWERY_SOURCE WHERE brewery_no=" + breweryNo);
                    execute(writer, "DELETE FROM BREWERY WHERE no=" + breweryNo);
                }
            }
        }
    }

    private static Connection connect() throws SQLException {
        String url = System.getenv("AUTH_TEST_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):3308/sakezuki_auth_it(?:\\?.*)?"));
        return DriverManager.getConnection(url, System.getenv("AUTH_TEST_DB_USER"), System.getenv("AUTH_TEST_DB_PASSWORD"));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
