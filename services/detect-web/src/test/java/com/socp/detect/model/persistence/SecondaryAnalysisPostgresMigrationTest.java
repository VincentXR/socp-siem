package com.socp.detect.model.persistence;

import com.socp.platform.test.MiddlewareImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** PostgreSQL compatibility evidence for the secondary-analysis schema retained from detect-model. */
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SecondaryAnalysisPostgresMigrationTest {

    @Test
    void legacyRowsUpgradeThroughV4AndReceiptIdentityRemainsUnique() throws Exception {
        try (GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
                .withEnv("POSTGRES_DB", "detect_model")
                .withEnv("POSTGRES_USER", "socp")
                .withEnv("POSTGRES_PASSWORD", "socp-test")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))) {
            postgres.start();
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/detect_model";

            migrate(url, "1");
            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_analyzed
                            (alert_id, rule_id, rule_name, severity, message, entity, ts)
                        VALUES
                            ('legacy-alert', 'legacy-rule', 'Legacy rule', 'HIGH',
                             'legacy result', 'host-a', CURRENT_TIMESTAMP)
                        """);
            }

            migrate(url, null);

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, """
                        SELECT COUNT(*) FROM t_analyzed
                        WHERE alert_id = 'legacy-alert' AND tenant_id = 'default'
                        """));

                statement.executeUpdate("""
                        INSERT INTO t_analysis_receipt
                            (tenant_id, source_alarm_id, analyzer_version, status, claimed_at)
                        VALUES ('tenant-a', 'alarm-1', 'v1', 'CLAIMED', CURRENT_TIMESTAMP)
                        """);
                assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO t_analysis_receipt
                            (tenant_id, source_alarm_id, analyzer_version, status, claimed_at)
                        VALUES ('tenant-a', 'alarm-1', 'v1', 'CLAIMED', CURRENT_TIMESTAMP)
                        """));
            }
        }
    }

    private static void migrate(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, "socp", "socp-test")
                .locations("classpath:db/secondary-analysis");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static int scalar(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
