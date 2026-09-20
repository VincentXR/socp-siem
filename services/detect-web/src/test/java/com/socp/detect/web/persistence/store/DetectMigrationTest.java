package com.socp.detect.web.persistence.store;


import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectMigrationTest {

    @Test
    void allMigrationsApplyToAnEmptyDatabase() throws Exception {
        String url = "jdbc:h2:mem:detect-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        String secondaryUrl = "jdbc:h2:mem:secondary-analysis-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(secondaryUrl, "sa", "")
                .locations("classpath:db/secondary-analysis")
                .load()
                .migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_NAME='T_RULE_CHANGE_OUTBOX'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES "
                             + "WHERE INDEX_NAME IN "
                             + "('IDX_DETECTION_EVENT_COMPLETED_RETENTION',"
                             + "'IDX_DETECTION_EVENT_DEAD_LETTER_RETENTION')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
        try (var connection = DriverManager.getConnection(secondaryUrl, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_NAME IN ('T_ANALYZED','T_ANALYSIS_RECEIPT')");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(2, result.getInt(1));
        }

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            // v2 allows several delivery identities for one source evidence id.
            String insert = "INSERT INTO t_detection_event "
                    + "(event_id,tenant_id,source_event_id,delivery_id,routing_version,"
                    + "source,host,fields_json,severity,occurred_at,status) "
                    + "VALUES (?,?,?,?,?,'auth','host','{}','INFO',CURRENT_TIMESTAMP,'COMPLETED')";
            try (var statement = connection.prepareStatement(insert)) {
                statement.setString(1, "storage-user");
                statement.setString(2, "tenant-a");
                statement.setString(3, "source-shared");
                statement.setString(4, "delivery-user");
                statement.setString(5, "detection-routing-v2");
                statement.executeUpdate();

                statement.setString(1, "storage-host");
                statement.setString(4, "delivery-host");
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM t_detection_event "
                            + "WHERE tenant_id='tenant-a' AND source_event_id='source-shared'");
                 var result = statement.executeQuery()) {
                result.next();
                assertEquals(2, result.getInt(1),
                        "journal uniqueness must be tenant + delivery, not tenant + source event");
            }
            assertThrows(java.sql.SQLException.class, () -> {
                try (var duplicate = connection.prepareStatement(insert)) {
                    duplicate.setString(1, "storage-duplicate");
                    duplicate.setString(2, "tenant-a");
                    duplicate.setString(3, "source-shared");
                    duplicate.setString(4, "delivery-user");
                    duplicate.setString(5, "detection-routing-v2");
                    duplicate.executeUpdate();
                }
            });
        }

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE TABLE_NAME='T_DETECTION_ROUTE_OUTBOX'");
             var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
