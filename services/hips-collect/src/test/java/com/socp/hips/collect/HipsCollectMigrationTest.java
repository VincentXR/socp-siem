package com.socp.hips.collect;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HipsCollectMigrationTest {

    @Test
    void migrationsCreateDurableHipsEventSchemaOnAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:hips_collect_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'T_HIPS_EVENT'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
