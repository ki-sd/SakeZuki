package com.sakezuki.tools.repository;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CatalogTransactionTests {
    @Test
    void isolationAndUnpairedSurrogateFailBeforeWrite() {
        assertThrows(SQLException.class, () -> GuideCatalogSource.BREWERY.save(connection(name -> {
            assertEquals("getTransactionIsolation", name);
            return Connection.TRANSACTION_READ_COMMITTED;
        }), "Valid", () -> fail("unexpected write")));
        assertThrows(SQLException.class, () -> GuideCatalogSource.BREWERY.save(connection(name ->
                fail("unexpected access")), "invalid\uD800", () -> fail("unexpected write")));
    }

    @Test
    void cleanupFailureAbortsAndFallsBackToCloseWithoutChangingPrimaryFailure() {
        var failure = new AssertionError("primary");
        var rollback = new SQLException("rollback failed");
        var abort = new SQLException("abort failed");
        var events = new ArrayList<String>();
        var c = mutexConnection(events, true, rollback, abort);
        assertSame(failure, assertThrows(AssertionError.class, () ->
                GuideCatalogSource.BREWERY.save(c, "Fixture", () -> { throw failure; })));
        assertEquals(List.of("setAutoCommit", "rollback", "abort", "close"), events);
        assertArrayEquals(new Throwable[]{rollback, abort}, failure.getSuppressed());
    }

    @Test
    void normalCallerFailureRestoresSavepointButDeadlockDoesNotTryRemovedSavepoint() {
        for (SQLException failure : List.of(new SQLException("conflict"), deadlock())) {
            var events = new ArrayList<String>();
            var c = mutexConnection(events, false, null, null);
            assertSame(failure, assertThrows(SQLException.class, () ->
                    GuideCatalogSource.BRAND.save(c, "Fixture", () -> { throw failure; })));
            assertEquals(CatalogTransaction.isDeadlock(failure) ? List.of("setSavepoint")
                    : List.of("setSavepoint", "rollback", "releaseSavepoint"), events);
        }
    }

    @Test
    void wholeTransactionRetriesAfterCloseAndPreservesFirstDeadlockOnExhaustion() throws Exception {
        var events = new ArrayList<String>();
        var failures = List.of(deadlock(), deadlock(), deadlock());
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var actual = assertThrows(SQLException.class, () -> CatalogTransaction.run(() -> {
            int attempt = attempts.getAndIncrement();
            events.add("open" + attempt);
            return connection(name -> {
                events.add(name + attempt);
                return null;
            });
        }, ignored -> { throw failures.get(attempts.get() - 1); }, attempt -> events.add("wait" + attempt)));
        assertSame(failures.getFirst(), actual);
        assertEquals("40001", actual.getSQLState());
        assertEquals(1213, actual.getErrorCode());
        assertArrayEquals(new Throwable[]{failures.get(1), failures.get(2)}, actual.getSuppressed());
        assertEquals(List.of("open0", "setAutoCommit0", "close0", "wait1", "open1", "setAutoCommit1",
                "close1", "wait2", "open2", "setAutoCommit2", "close2"), events);
    }

    @Test
    void retriesLockTimeoutAfterRollbackAndDoesNotRetryPermanentOrCommitFailures() throws Exception {
        var events = new ArrayList<String>();
        var tries = new java.util.concurrent.atomic.AtomicInteger();
        CatalogTransaction.run(() -> connection(name -> { events.add(name); return null; }),
                ignored -> { if (tries.incrementAndGet() == 1) throw new SQLException("timeout", "40001", 1205); },
                attempt -> events.add("wait"));
        assertEquals(List.of("setAutoCommit", "rollback", "close", "wait", "setAutoCommit", "commit", "close"), events);
        var permanent = new SQLException("FK", "23000", 1452);
        assertSame(permanent, assertThrows(SQLException.class, () ->
                CatalogTransaction.run(() -> connection(name -> null), ignored -> { throw permanent; },
                        ignored -> fail("permanent error retried"))));
        var unknown = new SQLException("commit response lost", "08006");
        assertSame(unknown, assertThrows(SQLException.class, () -> CatalogTransaction.run(
                () -> connection(name -> { if (name.equals("commit")) throw unchecked(unknown); return null; }),
                ignored -> {}, ignored -> fail("ambiguous commit retried"))));
    }

    @Test
    void uncertainRollbackPreventsRetryAndPreservesBothCleanupFailures() {
        var timeout = new SQLException("timeout", "40001", 1205);
        var rollback = new SQLException("rollback failed", "08006");
        var close = new SQLException("close failed", "08006");
        assertSame(timeout, assertThrows(SQLException.class, () -> CatalogTransaction.run(
                () -> connection(name -> {
                    if (name.equals("rollback")) throw unchecked(rollback);
                    if (name.equals("close")) throw unchecked(close);
                    return null;
                }), ignored -> { throw timeout; }, ignored -> fail("uncertain rollback retried"))));
        assertArrayEquals(new Throwable[]{rollback, close}, timeout.getSuppressed());
    }

    private static SQLException deadlock() { return new SQLException("deadlock", "40001", 1213); }

    private static Connection mutexConnection(List<String> events, boolean autoCommit, SQLException rollback, SQLException abort) {
        var marker = new Savepoint() {
            public int getSavepointId() { return 1; }
            public String getSavepointName() { return "fixture"; }
        };
        return connection(name -> switch (name) {
            case "getTransactionIsolation" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "getAutoCommit" -> autoCommit;
            case "prepareStatement" -> proxy(java.sql.PreparedStatement.class, statement ->
                    statement.equals("executeQuery") ? proxy(java.sql.ResultSet.class, cursor ->
                            cursor.equals("next") ? true : null) : null);
            default -> {
                events.add(name);
                if (name.equals("rollback") && rollback != null) throw unchecked(rollback);
                if (name.equals("abort") && abort != null) throw unchecked(abort);
                yield name.equals("setSavepoint") ? marker : null;
            }
        });
    }

    private static Connection connection(Function<String, Object> behavior) {
        return proxy(Connection.class, behavior);
    }

    private static <T> T proxy(Class<T> type, Function<String, Object> behavior) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (obj, method, args) -> {
            try {
                return behavior.apply(method.getName());
            } catch (java.util.concurrent.CompletionException wrapper) {
                throw wrapper.getCause();
            }
        }));
    }

    private static RuntimeException unchecked(SQLException failure) {
        return new java.util.concurrent.CompletionException(failure);
    }
}
