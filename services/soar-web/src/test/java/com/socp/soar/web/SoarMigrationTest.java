package com.socp.soar.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoarMigrationTest {

    @Test
    void migrationsCreateIdempotencyAndSoarV2ExecutionSchemasOnAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:soar_migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_name IN ('T_ALARM_EVALUATION', 'T_SCHEDULED_PLAYBOOK_RUN',"
                             + "'T_SOAR_PLAYBOOK', 'T_SOAR_PLAYBOOK_VERSION', 'T_SOAR_RUN', 'T_SOAR_DISPATCH_OUTBOX',"
                             + "'T_SOAR_NODE_RUN', 'T_SOAR_RUN_EVENT', 'T_SOAR_APPROVAL', 'T_SOAR_AUTOMATION_RULE',"
                             + "'T_SOAR_CONNECTOR', 'T_SOAR_TRIGGER_RECEIPT', 'T_SOAR_ACTION_ATTEMPT', 'T_SOAR_MANUAL_TASK',"
                             + "'T_SOAR_SIGNAL_OUTBOX', 'T_SOAR_ARTIFACT', 'T_SOAR_APPROVAL_DECISION')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(17, result.getInt(1));
            try (var migration = connection.prepareStatement(
                    "SELECT MAX(\"installed_rank\") FROM \"flyway_schema_history\"");
                 var migrationResult = migration.executeQuery()) {
                migrationResult.next();
                assertEquals(19, migrationResult.getInt(1));
            }
            try (var policyColumn = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = 'T_SOAR_APPROVAL' AND column_name = 'POLICY_JSON'");
                 var policyResult = policyColumn.executeQuery()) {
                policyResult.next();
                assertEquals(1, policyResult.getInt(1));
            }
            try (var remoteColumn = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name = 'T_SOAR_ACTION_ATTEMPT' AND column_name = 'REMOTE_TIME'");
                 var remoteResult = remoteColumn.executeQuery()) {
                remoteResult.next();
                assertEquals(1, remoteResult.getInt(1));
            }
            try (var connColumn = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE (table_name = 'T_SOAR_NODE_RUN' OR table_name = 'T_SOAR_ACTION_ATTEMPT') "
                            + "AND column_name IN ('CONNECTION_ID', 'CONNECTION_REVISION')");
                 var connResult = connColumn.executeQuery()) {
                connResult.next();
                assertEquals(4, connResult.getInt(1));
            }
        }
    }
}
