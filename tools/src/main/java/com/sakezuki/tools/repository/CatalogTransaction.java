package com.sakezuki.tools.repository;

import com.sakezuki.tools.model.BrandData;
import com.sakezuki.tools.model.BreweryData;
import com.sakezuki.tools.model.SakeData;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

public final class CatalogTransaction {
    private static final int MAX_ATTEMPTS = 3;

    private CatalogTransaction() {}

    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface Work {
        void save(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    interface Delay {
        void await(int failedAttempt) throws InterruptedException;
    }

    public static void save(Connections connections, SakeData sake, BreweryData brewery, BrandData brand)
            throws SQLException {
        run(connections, connection -> {
            long breweryNo = new BreweryRepository(connection).save(brewery);
            long brandNo = new BrandRepository(connection).save(brand, breweryNo);
            new SakeRepository(connection).save(sake, breweryNo, brandNo);
        }, attempt -> Thread.sleep((25L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(25)));
    }

    static boolean isDeadlock(SQLException failure) {
        return failure.getErrorCode() == 1213 && "40001".equals(failure.getSQLState());
    }

    static void run(Connections connections, Work work, Delay delay) throws SQLException {
        SQLException firstContention = null;
        for (int attempt = 1; ; attempt++) {
            SQLException retry = null;
            try (var connection = connections.open()) {
                connection.setAutoCommit(false);
                try {
                    work.save(connection);
                } catch (SQLException | RuntimeException | Error failure) {
                    boolean deadlock = failure instanceof SQLException sql && isDeadlock(sql);
                    boolean rolledBack = deadlock || rollback(connection, failure);
                    if (rolledBack && failure instanceof SQLException sql
                            && (deadlock || sql.getErrorCode() == 1205)) {
                        // Connector/J may map 1205 to class 40 despite server SQLSTATE HY000.
                        // Throw through try-with-resources so close failures are suppressed on the real error.
                        retry = sql;
                    }
                    throw failure;
                }
                // Commit errors can have an unknown outcome, so they never enter the retry path.
                connection.commit();
                return;
            } catch (SQLException failure) {
                if (failure != retry) {
                    if (firstContention != null && firstContention != failure) {
                        failure.addSuppressed(firstContention);
                    }
                    throw failure;
                }
                if (firstContention == null) {
                    firstContention = failure;
                } else if (firstContention != failure) {
                    firstContention.addSuppressed(failure);
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw firstContention;
                }
                try {
                    delay.await(attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    firstContention.addSuppressed(interrupted);
                    throw firstContention;
                }
            }
        }
    }

    private static boolean rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) {
                failure.addSuppressed(cleanupFailure);
            }
            return false;
        }
    }
}
