package com.sakezuki.tools.repository;

import java.sql.Connection;
import java.sql.SQLException;

enum GuideCatalogSource {
    SAKE("SAKE", "sake_no"),
    BREWERY("BREWERY", "brewery_no"),
    BRAND("BRAND", "brand_no");

    private final String ownerPredicate;
    private final String register;

    GuideCatalogSource(String table, String foreignKey) {
        // Match the ID actually stored by the legacy upsert, preserving its collation and spelling.
        ownerPredicate = """
                EXISTS (SELECT 1 FROM %1$s_SOURCE src
                        WHERE src.%2$s = %1$s.no AND src.source_system = 'SAKE_GUIDE'
                          AND src.external_key = CAST(%1$s.source_id AS CHAR CHARACTER SET utf8mb4)
                              COLLATE utf8mb4_0900_bin)
                """.formatted(table, foreignKey).strip();
        register = """
                INSERT INTO %1$s_SOURCE (%2$s, source_system, external_key)
                SELECT no, 'SAKE_GUIDE', CAST(source_id AS CHAR CHARACTER SET utf8mb4)
                FROM %1$s WHERE no = ?
                ON DUPLICATE KEY UPDATE %2$s = %1$s_SOURCE.%2$s
                """.formatted(table, foreignKey);
        this.table = table;
    }

    private final String table;

    String predicate() {
        return ownerPredicate;
    }

    @FunctionalInterface
    interface CatalogWrite {
        long save() throws SQLException;
    }

    long save(Connection connection, CatalogWrite write) throws SQLException {
        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) {
            connection.setAutoCommit(false);
        }
        var checkpoint = ownsTransaction ? null : connection.setSavepoint();
        long no;
        try {
            no = write.save();
            try (var statement = connection.prepareStatement(register)) {
                statement.setLong(1, no);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE no = ? AND " + ownerPredicate)) {
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
        } catch (SQLException | RuntimeException failure) {
            try {
                if (ownsTransaction) {
                    connection.rollback();
                    connection.setAutoCommit(true);
                } else {
                    connection.rollback(checkpoint);
                    connection.releaseSavepoint(checkpoint);
                }
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                // Do not restore auto-commit after an unsuccessful rollback.
            }
            throw failure;
        }
        if (ownsTransaction) {
            connection.setAutoCommit(true);
        }
        return no;
    }
}
