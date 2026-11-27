package com.sakezuki.tools.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

enum GuideCatalogSource {
    SAKE("SAKE", "sake_no", "sake", false),
    BREWERY("BREWERY", "brewery_no", "brewery", true),
    BRAND("BRAND", "brand_no", "brand", true);

    private static final Pattern FORBIDDEN_KEY_TEXT = Pattern.compile(
            "^\\p{IsWhite_Space}|\\p{IsWhite_Space}$|[\\p{Cc}\\p{Cs}]");

    private final String ownerPredicate;
    private final String lockRequestedOwners;
    private final String register;
    private final String verifyOwner;

    GuideCatalogSource(String table, String foreignKey, String indexSuffix, boolean legacyTextId) {
        // Match the ID actually stored by the legacy upsert, preserving its collation and spelling.
        String sameIdentity = """
                src.%2$s = %1$s.no AND src.source_system = 'SAKE_GUIDE'
                AND src.external_key = CAST(%1$s.source_id AS CHAR CHARACTER SET utf8mb4)
                    COLLATE utf8mb4_0900_bin
                """.formatted(table, foreignKey).strip();
        ownerPredicate = "EXISTS (SELECT 1 FROM " + table + "_SOURCE src WHERE " + sameIdentity + ")";
        // A current read avoids using an older REPEATABLE READ snapshot after an upsert.
        verifyOwner = "SELECT 1 FROM " + table + " JOIN " + table + "_SOURCE src ON " + sameIdentity
                + " WHERE " + table + ".no = ? FOR SHARE";
        String keyColumn = legacyTextId ? "legacy_key" : "external_key";
        String index = legacyTextId ? "ix_" + indexSuffix + "_source_legacy_key"
                : "uq_" + indexSuffix + "_source_external";
        String collation = legacyTextId ? "utf8mb4_0900_ai_ci" : "utf8mb4_0900_bin";
        lockRequestedOwners = """
                SELECT %s FROM %s_SOURCE FORCE INDEX (%s)
                WHERE source_system = 'SAKE_GUIDE'
                  AND %s = CAST(? AS CHAR CHARACTER SET utf8mb4) COLLATE %s
                FOR SHARE
                """.formatted(foreignKey, table, index, keyColumn, collation);
        register = """
                INSERT INTO %1$s_SOURCE (%2$s, source_system, external_key)
                SELECT no, 'SAKE_GUIDE', CAST(source_id AS CHAR CHARACTER SET utf8mb4)
                FROM %1$s WHERE no = ?
                ON DUPLICATE KEY UPDATE %2$s = %1$s_SOURCE.%2$s
                """.formatted(table, foreignKey);
    }

    String predicate() {
        return ownerPredicate;
    }

    @FunctionalInterface
    interface CatalogWrite {
        long save() throws SQLException;
    }

    long save(Connection connection, String requestedKey, CatalogWrite write) throws SQLException {
        if (requestedKey == null || requestedKey.isEmpty()
                || requestedKey.codePointCount(0, requestedKey.length()) > 255
                || FORBIDDEN_KEY_TEXT.matcher(requestedKey).find()) {
            throw new SQLException("Invalid SAKE_GUIDE external key");
        }
        int isolation = connection.getTransactionIsolation();
        if (isolation != Connection.TRANSACTION_REPEATABLE_READ && isolation != Connection.TRANSACTION_SERIALIZABLE) {
            throw new SQLException("SAKE_GUIDE writes require REPEATABLE READ or SERIALIZABLE");
        }
        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) {
            connection.setAutoCommit(false);
        }
        var checkpoint = ownsTransaction ? null : connection.setSavepoint();
        long no;
        try {
            // Serialize Guide writers before any catalog write. Register before the range read
            // so a fresh identity never upgrades its own missing-key gap lock into an insert.
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM GUIDE_CATALOG_WRITE_MUTEX WHERE id = 1 FOR UPDATE");
                 var lock = statement.executeQuery()) {
                if (!lock.next()) {
                    throw new SQLException("Guide catalog write mutex is missing");
                }
            }
            no = write.save();
            try (var statement = connection.prepareStatement(register)) {
                statement.setLong(1, no);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(lockRequestedOwners)) {
                statement.setString(1, requestedKey);
                try (var owners = statement.executeQuery()) {
                    while (owners.next()) {
                        if (owners.getLong(1) != no) {
                            throw new SQLException("Requested SAKE_GUIDE key conflicts with another catalog owner");
                        }
                    }
                }
            }
            try (var statement = connection.prepareStatement(verifyOwner)) {
                statement.setLong(1, no);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("SAKE_GUIDE identity belongs to another catalog row");
                    }
                }
            }
            if (ownsTransaction) {
                connection.commit();
            } else {
                connection.releaseSavepoint(checkpoint);
            }
        } catch (SQLException | RuntimeException | Error failure) {
            // InnoDB already rolled back the caller's entire deadlocked transaction.
            if (!ownsTransaction && failure instanceof SQLException sql && CatalogTransaction.isDeadlock(sql)) {
                throw failure;
            }
            try {
                if (ownsTransaction) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } else {
                    connection.rollback(checkpoint);
                    connection.releaseSavepoint(checkpoint);
                }
            } catch (SQLException | RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                discard(connection, failure);
            }
            throw failure;
        }
        if (ownsTransaction) {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException | RuntimeException | Error failure) {
                discard(connection, failure);
                throw failure;
            }
        }
        return no;
    }

    private static void discard(Connection connection, Throwable failure) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException | Error abortFailure) {
            failure.addSuppressed(abortFailure);
            try {
                connection.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }
}
