package com.socp.alert.persistence.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/** Insert-only, transaction-bound claims. SQL identifiers are internal constants, never request input. */
final class ConflictSafeInsert {
    private ConflictSafeInsert() { }

    static boolean insert(JdbcTemplate jdbc, String table, String uniqueConstraint, List<String> columns,
                          List<String> identity, Object... values) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("idempotent insert requires an active transaction");
        }
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            String names = String.join(",", columns);
            String parameters = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
            String sql;
            boolean h2 = connection.getMetaData().getDatabaseProductName().startsWith("H2");
            if (h2) {
                String predicate = identity.stream().map(key -> "target." + key + "=incoming." + key)
                        .collect(Collectors.joining(" AND "));
                String incoming = columns.stream().map(column -> "incoming." + column).collect(Collectors.joining(","));
                sql = "MERGE INTO " + table + " target USING (VALUES (" + parameters + ")) incoming (" + names
                        + ") ON " + predicate + " WHEN NOT MATCHED THEN INSERT (" + names + ") VALUES (" + incoming + ")";
            } else {
                sql = "INSERT INTO " + table + " (" + names + ") VALUES (" + parameters + ") ON CONFLICT ("
                        + String.join(",", identity) + ") DO NOTHING";
            }
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < values.length; i++) {
                    Object value = values[i];
                    if (value instanceof Instant instant) value = instant.atOffset(ZoneOffset.UTC);
                    if (value == null) statement.setNull(i + 1, Types.NULL);
                    else statement.setObject(i + 1, value);
                }
                try {
                    return statement.executeUpdate() == 1;
                } catch (java.sql.SQLException conflict) {
                    // H2 MERGE can lose a concurrent insert race. Unlike PostgreSQL,
                    // H2 rolls back only that statement and keeps this transaction usable.
                    // Only the intended identity constraint means an idempotent loser.
                    if (h2 && "23505".equals(conflict.getSQLState()) && conflict.getMessage() != null
                            && conflict.getMessage().toUpperCase(java.util.Locale.ROOT)
                            .contains(uniqueConstraint.toUpperCase(java.util.Locale.ROOT))) return false;
                    throw conflict;
                }
            }
        }));
    }
}
