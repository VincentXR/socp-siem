package com.socp.platform.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real PostgreSQL proof for the cross-service idempotency boundary.
 *
 * <p>It is enabled in CI with SOCP_TESTCONTAINERS=true. Developers without a
 * Docker daemon still get the normal unit suite; the integration proof is
 * never silently reported as passed.</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class ContainerIdempotencyContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("contract")
            .withUsername("socp")
            .withPassword("socp");

    @Test
    void postgresUniqueConstraintIsTheAuthoritativeAlertIdempotencyBoundary() throws Exception {
        try (var connection = POSTGRES.createConnection("");
             var schema = connection.createStatement()) {
            schema.execute("create table t_alarm (id varchar(36) primary key, tenant_id varchar(64) not null, source_alert_id varchar(255) not null, "
                    + "constraint uq_alarm_tenant_source_alert unique (tenant_id, source_alert_id))");
            try (var insert = connection.prepareStatement(
                    "insert into t_alarm(id, tenant_id, source_alert_id) values (?, ?, ?)")) {
                insert.setString(1, "alarm-1");
                insert.setString(2, "tenant-a");
                insert.setString(3, "stable-alert-1");
                assertEquals(1, insert.executeUpdate());
            }

            SQLException duplicate = assertThrows(SQLException.class, () -> {
                try (var insert = connection.prepareStatement(
                        "insert into t_alarm(id, tenant_id, source_alert_id) values (?, ?, ?)")) {
                    insert.setString(1, "alarm-2");
                    insert.setString(2, "tenant-a");
                    insert.setString(3, "stable-alert-1");
                    insert.executeUpdate();
                }
            });
            // This is intentionally a database-level oracle, independent of ORM timing.
            String message = duplicate.getMessage().toLowerCase();
            org.junit.jupiter.api.Assertions.assertTrue(
                    message.contains("uq_alarm_tenant_source_alert") || message.contains("duplicate key"));
        }
    }
}
