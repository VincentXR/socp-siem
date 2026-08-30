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
        }
    }
}
