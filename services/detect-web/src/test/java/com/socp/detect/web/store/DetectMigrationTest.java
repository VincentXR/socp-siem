package com.socp.detect.web.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DetectMigrationTest {

    @Test
    void allMigrationsApplyToAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:detect-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_NAME='T_RULE_CHANGE_OUTBOX'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
