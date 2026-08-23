package com.socp.soc.audit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditMigrationTest {

    @Test
    void migrationsCreateDurableEventIdentityOnAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:audit-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME='T_AUDIT' AND COLUMN_NAME='EVENT_ID' AND IS_NULLABLE='NO'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
