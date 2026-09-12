package com.socp.soar.web.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL evidence for the SOAR migration chain and tenant-owned
 * execution graph.  The normal unit suite deliberately skips this test when
 * Docker is unavailable; CI enables it with SOCP_TESTCONTAINERS=true.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SoarPostgresMigrationContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("soar_contract")
            .withUsername("socp")
            .withPassword("socp-test");

    @Test
    void migrationsApplyIdempotentlyAndDatabaseRejectsCrossTenantOwnership() throws Exception {
        String url = POSTGRES.getJdbcUrl();
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // A second invocation is the upgrade/restart contract for an already
        // provisioned service database.
        Flyway.configure()
                .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertEquals(22, scalar(statement,
                    "SELECT MAX(installed_rank) FROM flyway_schema_history"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() "
                            + "AND table_name = 't_soar_run' "
                            + "AND column_name = 'execution_node_count'"));
            assertEquals(18, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.table_constraints "
                            + "WHERE table_schema = current_schema() "
                            + "AND constraint_type = 'FOREIGN KEY' "
                            + "AND constraint_name LIKE 'fk_soar_%'"));

            statement.executeUpdate("""
                    INSERT INTO t_soar_playbook
                        (id, tenant_id, name, status, row_version, created_at, updated_at)
                    VALUES ('pb-a', 'tenant-a', 'Contract playbook', 'PUBLISHED', 0,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO t_soar_playbook_version
                        (id, tenant_id, playbook_id, version_no, status, schema_version,
                         definition_json, definition_hash, created_by, created_at,
                         updated_at, row_version)
                    VALUES ('version-a', 'tenant-a', 'pb-a', 1, 'PUBLISHED', '2.0',
                            '{}', 'hash-a', 'contract-test', CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP, 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO t_soar_run
                        (id, tenant_id, request_id, execution_series_id, playbook_id,
                         playbook_version_id, playbook_version_no, definition_hash,
                         trigger_type, status, requested_by, created_at, updated_at)
                    VALUES ('run-a', 'tenant-a', 'request-a', 'series-a', 'pb-a',
                            'version-a', 1, 'hash-a', 'CONTRACT', 'ACCEPTED',
                            'contract-test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO t_soar_automation_rule
                        (id, tenant_id, name, enabled, priority, trigger_type,
                         condition_json, actions_json, created_by, created_at,
                         updated_at, row_version, revision)
                    VALUES ('rule-a', 'tenant-a', 'Contract rule', true, 1, 'ALERT',
                            '{}', '[\"version-a\"]', 'contract-test', CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP, 0, 1)
                    """);

            SQLException crossTenant = assertThrows(SQLException.class, () ->
                    statement.executeUpdate("""
                            INSERT INTO t_soar_run
                                (id, tenant_id, request_id, execution_series_id, playbook_id,
                                 playbook_version_id, playbook_version_no, definition_hash,
                                 trigger_type, status, requested_by, created_at, updated_at)
                            VALUES ('run-b', 'tenant-b', 'request-b', 'series-b', 'pb-a',
                                    'version-a', 1, 'hash-a', 'CONTRACT', 'ACCEPTED',
                                    'contract-test', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """));
            assertEquals("23503", crossTenant.getSQLState());
            assertTrue(crossTenant.getMessage().toLowerCase().contains("fk_soar_run"),
                    crossTenant.getMessage());

            statement.executeUpdate("""
                    INSERT INTO t_soar_trigger_receipt
                        (id, tenant_id, event_id, automation_rule_id, rule_revision,
                         status, run_id, created_at, updated_at)
                    VALUES ('receipt-a', 'tenant-a', 'event-a', 'rule-a', 1,
                            'ACCEPTED', 'run-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            SQLException duplicateReceipt = assertThrows(SQLException.class, () ->
                    statement.executeUpdate("""
                            INSERT INTO t_soar_trigger_receipt
                                (id, tenant_id, event_id, automation_rule_id, rule_revision,
                                 status, run_id, created_at, updated_at)
                            VALUES ('receipt-b', 'tenant-a', 'event-a', 'rule-a', 1,
                                    'ACCEPTED', 'run-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """));
            assertEquals("23505", duplicateReceipt.getSQLState());

            // Simulate two service instances evaluating the same enabled rule:
            // the first transaction owns the row lock, while NOWAIT proves a
            // second transaction cannot observe/admit the same capacity slot.
            try (var owner = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 var contender = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                 var ownerStatement = owner.createStatement();
                 var contenderStatement = contender.createStatement()) {
                owner.setAutoCommit(false);
                contender.setAutoCommit(false);
                try (var locked = ownerStatement.executeQuery("""
                        SELECT id FROM t_soar_automation_rule
                        WHERE tenant_id = 'tenant-a' AND enabled = true AND id = 'rule-a'
                        FOR UPDATE
                        """)) {
                    assertTrue(locked.next());
                }
                SQLException lockConflict = assertThrows(SQLException.class, () -> {
                    try (var ignored = contenderStatement.executeQuery("""
                            SELECT id FROM t_soar_automation_rule
                            WHERE tenant_id = 'tenant-a' AND enabled = true AND id = 'rule-a'
                            FOR UPDATE NOWAIT
                            """)) {
                        // The NOWAIT query must fail while the owner holds the row.
                    }
                });
                assertEquals("55P03", lockConflict.getSQLState());
                owner.commit();
                contender.rollback();
                try (var acquired = contenderStatement.executeQuery("""
                        SELECT id FROM t_soar_automation_rule
                        WHERE tenant_id = 'tenant-a' AND enabled = true AND id = 'rule-a'
                        FOR UPDATE
                        """)) {
                    assertTrue(acquired.next());
                }
                contender.commit();
            }
        }
    }

    private static int scalar(java.sql.Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
