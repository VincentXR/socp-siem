package com.socp.search.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchConfigMigrationTest {

    @Test
    void ingestionOutboxHasPredicateAndTenantDueIndexes() throws Exception {
        String url = "jdbc:h2:mem:search-config-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                             + "WHERE INDEX_NAME IN "
                             + "('IDX_INGESTION_OUTBOX_DUE_V2',"
                             + "'IDX_INGESTION_OUTBOX_TENANT_DUE')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));

            String largeValue = "x".repeat(5_000);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO t_search_event "
                            + "(id, msg, fields_json, ecs_json, tenant_id) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, "large-event");
                insert.setString(2, largeValue);
                insert.setString(3, largeValue);
                insert.setString(4, largeValue);
                insert.setString(5, "default");
                assertEquals(1, insert.executeUpdate());
            }
            try (var lengths = connection.prepareStatement(
                    "SELECT LENGTH(msg), LENGTH(fields_json), LENGTH(ecs_json) "
                            + "FROM t_search_event WHERE id = 'large-event'")) {
                try (var values = lengths.executeQuery()) {
                    values.next();
                    assertEquals(5_000, values.getInt(1));
                    assertEquals(5_000, values.getInt(2));
                    assertEquals(5_000, values.getInt(3));
                }
            }
        }
    }
}
