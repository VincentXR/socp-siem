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

    @Test
    void upgradeBackfillsLegacyJsonAssociationsBeforeCompactingColumns() throws Exception {
        String url = "jdbc:h2:mem:incident_association_upgrade;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("7").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO t_case
                        (id, tenant_id, title, severity, status, rule_ids, alarm_ids,
                         timeline, created_at, updated_at, row_version)
                    VALUES ('legacy-case', 'tenant-a', 'legacy', 'LOW', 'OPEN',
                            '["RULE-1","RULE-2"]', '["AL-1","AL-2"]', '[]',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO t_alarm_case_link (id, tenant_id, alarm_id, case_id, created_at)
                    VALUES ('existing-alarm-link', 'tenant-a', 'AL-1', 'legacy-case', CURRENT_TIMESTAMP)
                    """);
        }

        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertEquals(2, scalar(statement,
                    "SELECT COUNT(*) FROM t_alarm_case_link WHERE case_id='legacy-case'"));
            assertEquals(2, scalar(statement,
                    "SELECT COUNT(*) FROM t_case_rule_link WHERE case_id='legacy-case'"));
            try (var rows = statement.executeQuery(
                    "SELECT alarm_ids, rule_ids FROM t_case WHERE id='legacy-case'")) {
                rows.next();
                assertEquals("[]", rows.getString(1));
                assertEquals("[]", rows.getString(2));
            }
        }
    }

    private static int scalar(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
