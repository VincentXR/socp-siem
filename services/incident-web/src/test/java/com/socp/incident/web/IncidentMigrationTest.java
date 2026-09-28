package com.socp.incident.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncidentMigrationTest {

    @Test
    void migrationsCreateAlarmCaseLinkSchemaOnAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:incident_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_name IN ('T_ALARM_CASE_LINK', 'T_CASE_RULE_LINK', "
                             + "'T_INCIDENT_MERGE_LOCK', 'T_AUDIT_OUTBOX')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(4, result.getInt(1));
        }
    }
}
