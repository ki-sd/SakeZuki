package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandData;
import com.sakezuki.tools.model.BreweryData;
import com.sakezuki.tools.model.SakeData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sakezuki.tools.repository.GuideCatalogMySqlTests.connect;
import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql-integration")
class GuideCatalogConcurrencyTests {
    @Test
    void oldGapThenInsertOrderingDeadlocksForDifferentKeysInSameGap() throws Exception {
        for (String table : List.of("BREWERY", "BRAND", "SAKE")) {
            var barrier = new CyclicBarrier(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> oldOrdering(table, 1, barrier));
                var second = pool.submit(() -> oldOrdering(table, 2, barrier));
                var results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
                assertEquals(1L, results.stream().filter(code -> code == 1213).count(), table + ": " + results);
                assertTrue(results.contains(0), table + ": " + results);
                System.out.printf("old ordering %s: %s%n", table, results);
            }
        }
    }

    private int oldOrdering(String table, int suffix, CyclicBarrier barrier) throws Exception {
        String entity = table.toLowerCase(Locale.ROOT);
        String key = table.equals("SAKE") ? "820000000" + suffix : "zzzQaOldGap" + suffix;
        String columns = table.equals("BREWERY") ? "source_id,name_ja" : "source_id,name_ja,brewery_no";
        String values = table.equals("BREWERY") ? "?,?" : "?,?,(SELECT MIN(no) FROM BREWERY)";
        try (var c = connect()) {
            c.setAutoCommit(false);
            try {
                try (var p = c.prepareStatement("INSERT INTO " + table + "(" + columns + ") VALUES(" + values + ")",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, key);
                    p.setString(2, "QaOldGap");
                    p.executeUpdate();
                    try (var generated = p.getGeneratedKeys()) {
                        assertTrue(generated.next());
                        long no = generated.getLong(1);
                        String keyColumn = table.equals("SAKE") ? "external_key" : "legacy_key";
                        String index = table.equals("SAKE") ? "uq_sake_source_external" : "ix_" + entity + "_source_legacy_key";
                        try (var read = c.prepareStatement("SELECT no FROM " + table + "_SOURCE FORCE INDEX (" + index
                                + ") WHERE source_system='SAKE_GUIDE' AND " + keyColumn + "=? FOR UPDATE")) {
                            read.setString(1, key);
                            try (var absent = read.executeQuery()) { assertFalse(absent.next()); }
                        }
                        barrier.await(10, TimeUnit.SECONDS);
                        try (var insert = c.prepareStatement("INSERT INTO " + table + "_SOURCE(" + entity
                                + "_no,source_system,external_key) VALUES(?,'SAKE_GUIDE',?)")) {
                            insert.setLong(1, no);
                            insert.setString(2, key);
                            insert.executeUpdate();
                        }
                    }
                }
                return 0;
            } catch (SQLException failure) {
                if (!CatalogTransaction.isDeadlock(failure)) throw failure;
                return failure.getErrorCode();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void secondWriterWaitsBeforeAnyCatalogWriteAtBothIsolationLevels() throws Exception {
        for (int isolation : List.of(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE)) {
            try (var first = connect(); var second = connect(); var workers = Executors.newSingleThreadExecutor()) {
                first.setTransactionIsolation(isolation);
                second.setTransactionIsolation(isolation);
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                var atMutex = new CountDownLatch(1);
                var attemptedUpsert = new AtomicBoolean();
                var observed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                                if (sql.contains("GUIDE_CATALOG_WRITE_MUTEX")) atMutex.countDown();
                                if (sql.contains("INSERT INTO BREWERY (")) attemptedUpsert.set(true);
                            }
                            try {
                                return method.invoke(second, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
                try {
                    var firstNo = new BreweryRepository(first).save(brewery("zzzQaGateA" + isolation));
                    var pending = workers.submit(() ->
                            new BreweryRepository(observed).save(brewery("zzzQaGateB" + isolation)));
                    assertTrue(atMutex.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> pending.get(150, TimeUnit.MILLISECONDS));
                    assertFalse(attemptedUpsert.get(), "second writer must not reach the catalog upsert");
                    first.rollback();
                    assertNotEquals(firstNo, pending.get(10, TimeUnit.SECONDS));
                    assertTrue(attemptedUpsert.get());
                } finally {
                    first.rollback();
                    second.rollback();
                }
            }
        }
    }

    @Test
    void threeWorkersFifteenRoundsPreserveOwnersAndNoPartialRows() throws Exception {
        var attempts = new AtomicInteger();
        var deadlocks = new AtomicInteger();
        var retries = new AtomicInteger();
        var completed = new AtomicInteger();
        try (var db = connect(); var pool = Executors.newFixedThreadPool(3)) {
            try {
                for (int round = 0; round < 15; round++) {
                    var start = new CyclicBarrier(3);
                    var jobs = new ArrayList<java.util.concurrent.Future<Void>>();
                    for (int worker = 0; worker < 3; worker++) {
                        int i = 3 * round + worker;
                        jobs.add(pool.submit(() -> {
                            start.await(10, TimeUnit.SECONDS);
                            CatalogTransaction.run(GuideCatalogMySqlTests::connect, c -> {
                                attempts.incrementAndGet();
                                try {
                                    saveGraph(c, i, false);
                                } catch (SQLException failure) {
                                    if (CatalogTransaction.isDeadlock(failure)) deadlocks.incrementAndGet();
                                    throw failure;
                                }
                            }, ignored -> retries.incrementAndGet());
                            completed.incrementAndGet();
                            return null;
                        }));
                    }
                    for (var job : jobs) job.get(20, TimeUnit.SECONDS);
                }
                var start = new CyclicBarrier(3);
                var mixed = new ArrayList<java.util.concurrent.Future<Void>>();
                for (int worker = 0; worker < 3; worker++) {
                    int i = worker;
                    mixed.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        CatalogTransaction.run(GuideCatalogMySqlTests::connect,
                                c -> saveGraph(c, i == 2 ? 45 : 0, i == 1), ignored -> retries.incrementAndGet());
                        return null;
                    }));
                }
                for (var job : mixed) job.get(20, TimeUnit.SECONDS);

                int failed = 45 - completed.get();
                System.out.printf("3x15 attempts=%d deadlocks=%d retries=%d success=%d failure=%d%n",
                        attempts.get(), deadlocks.get(), retries.get(), completed.get(), failed);
                assertEquals(45, attempts.get());
                assertEquals(0, deadlocks.get());
                assertEquals(0, retries.get());
                assertEquals(0, failed);
                for (String table : List.of("SAKE", "BRAND", "BREWERY")) {
                    assertEquals(46, count(db, "SELECT COUNT(*) FROM " + table + " WHERE " + filter(table, "source_id")));
                    String entity = table.toLowerCase(Locale.ROOT);
                    assertEquals(46, count(db, "SELECT COUNT(*) FROM " + table + "_SOURCE WHERE " +
                            filter(table, "external_key") + " AND source_system='SAKE_GUIDE'"));
                    assertEquals(46, count(db, "SELECT COUNT(*) FROM " + table + " c JOIN " + table + "_SOURCE s ON s."
                            + entity + "_no=c.no AND s.source_system='SAKE_GUIDE' AND s.external_key="
                            + "CAST(c.source_id AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_0900_bin WHERE "
                            + filter(table, "c.source_id")));
                }
                assertThrows(SQLException.class, () -> CatalogTransaction.run(GuideCatalogMySqlTests::connect, c -> {
                    long no = new BreweryRepository(c).save(brewery("zzzQaWave101"));
                    new BrandRepository(c).save(brand("zzzQaWave101"), no);
                    new SakeRepository(c).save(sake(101), no, 0); // FK failure after upstream writes.
                }, ignored -> fail("FK failure was retried")));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM BREWERY WHERE source_id='zzzQaWave101'"));
                assertEquals(0, count(db, "SELECT COUNT(*) FROM BRAND_SOURCE WHERE external_key='zzzQaWave101'"));
            } finally {
                for (String table : List.of("SAKE", "BRAND", "BREWERY")) {
                    try (var s = db.createStatement()) {
                        s.executeUpdate("DELETE FROM " + table + "_SOURCE WHERE " + filter(table, "external_key"));
                        s.executeUpdate("DELETE FROM " + table + " WHERE " + filter(table, "source_id"));
                    }
                }
            }
        }
    }

    @Test
    void realLockTimeoutReleasesFailedConnectionBeforeRetryingWholeGraph() throws Exception {
        try (var blocker = connect(); var db = connect()) {
            blocker.setAutoCommit(false);
            assertEquals(1, count(blocker, "SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id=1 FOR UPDATE"));
            var attempts = new AtomicInteger();
            var retries = new AtomicInteger();
            var timeout = new AtomicInteger();
            var connectionIds = new ArrayList<Long>();
            try {
                CatalogTransaction.run(() -> {
                    var connection = connect();
                    try {
                        try (var statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout=1");
                        }
                        connectionIds.add(count(connection, "SELECT CONNECTION_ID()"));
                        return connection;
                    } catch (SQLException failure) {
                        try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                        throw failure;
                    }
                }, connection -> {
                    attempts.incrementAndGet();
                    try {
                        saveGraph(connection, 90, false);
                    } catch (SQLException failure) {
                        timeout.set(failure.getErrorCode());
                        throw failure;
                    }
                }, ignored -> {
                    retries.incrementAndGet();
                    try {
                        blocker.rollback();
                    } catch (SQLException failure) {
                        throw new AssertionError(failure);
                    }
                });
                assertEquals(1205, timeout.get());
                assertEquals(2, attempts.get());
                assertEquals(1, retries.get());
                assertNotEquals(connectionIds.get(0), connectionIds.get(1));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM SAKE WHERE source_id=8300000090"));
                System.out.printf("real timeout attempts=%d retries=%d code=%d%n",
                        attempts.get(), retries.get(), timeout.get());
            } finally {
                blocker.rollback();
                for (String table : List.of("SAKE", "BRAND", "BREWERY")) {
                    try (var s = db.createStatement()) {
                        String key = table.equals("SAKE") ? "8300000090" : "zzzQaWave90";
                        s.executeUpdate("DELETE FROM " + table + "_SOURCE WHERE external_key='" + key + "'");
                        s.executeUpdate("DELETE FROM " + table + " WHERE source_id='" + key + "'");
                    }
                }
            }
        }
    }

    private static String filter(String table, String column) {
        if (!table.equals("SAKE")) return column + " LIKE 'zzzQaWave%'";
        return column.equals("external_key") ? column + " LIKE '8300000%'" : column + " BETWEEN 8300000000 AND 8300000200";
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (var statement = c.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void saveGraph(Connection connection, int i, boolean lowerCase) throws SQLException {
        String key = (lowerCase ? "zzzqawave" : "zzzQaWave") + i;
        long b = new BreweryRepository(connection).save(brewery(key));
        long brand = new BrandRepository(connection).save(brand(key), b);
        new SakeRepository(connection).save(sake(i), b, brand);
    }

    private static BreweryData brewery(String key) {
        return BreweryData.builder().sourceId(key).nameJa("Qa Brewery").build();
    }

    private static BrandData brand(String key) {
        return BrandData.builder().sourceId(key).nameJa("Qa Brand").build();
    }

    private static SakeData sake(int i) {
        return SakeData.builder().sourceId(8_300_000_000L + i).nameJa("Qa Sake").build();
    }
}
