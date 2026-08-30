package com.socp.detect.web.persistence.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PostgreSQL evidence for legacy terminal rows and state-aware journal cleanup. */
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class DetectJournalPostgresMigrationTest {

    @Test
    void populatedV4UpgradeCleansTerminalRowsInBatchesAndPreservesPending() throws Exception {
        try (GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withEnv("POSTGRES_DB", "detect")
                .withEnv("POSTGRES_USER", "socp")
                .withEnv("POSTGRES_PASSWORD", "socp-test")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))) {
            postgres.start();
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/detect";
            Flyway.configure()
                    .dataSource(url, "socp", "socp-test")
                    .locations("classpath:db/migration")
                    .target("4")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_detection_event
                            (event_id, source, host, raw_event, fields_json, severity,
                             occurred_at, kafka_partition, kafka_offset, routing_key)
                        SELECT 'legacy-' || n, 'auth', 'host-' || n, 'raw', '{}', 'INFO',
                               CURRENT_TIMESTAMP - INTERVAL '120 days', 0, n, 'default|host|' || n
                        FROM generate_series(1, 100000) AS n
                        """);
            }

            Flyway.configure()
                    .dataSource(url, "socp", "socp-test")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_detection_event
                            (event_id, tenant_id, source_event_id, source, host, raw_event,
                             fields_json, severity, occurred_at, status)
                        SELECT 'pending-' || n, 'tenant-a', 'pending-' || n, 'auth', 'host', 'raw',
                               '{}', 'INFO', CURRENT_TIMESTAMP - INTERVAL '120 days', 'PENDING'
                        FROM generate_series(1, 1000) AS n
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_detection_event
                            (event_id, tenant_id, source_event_id, source, host, raw_event,
                             fields_json, severity, occurred_at, status)
                        SELECT 'dead-' || n, 'tenant-a', 'dead-' || n, 'auth', 'host', 'raw',
                               '{}', 'INFO', CURRENT_TIMESTAMP - INTERVAL '120 days', 'DEAD_LETTERED'
                        FROM generate_series(1, 100) AS n
                        """);

                assertEquals(100_000, scalar(statement, """
                        SELECT COUNT(*) FROM t_detection_event
                        WHERE status = 'COMPLETED' AND completed_at IS NULL
                        """));
                assertEquals(100, scalar(statement, """
                        SELECT COUNT(*) FROM t_detection_event
                        WHERE status = 'DEAD_LETTERED' AND dead_lettered_at IS NULL
                        """));

                int completedDeleted = statement.executeUpdate("""
                        DELETE FROM t_detection_event WHERE event_id IN (
                            SELECT event_id FROM t_detection_event
                            WHERE status = 'COMPLETED'
                              AND (completed_at < CURRENT_TIMESTAMP
                                   OR (completed_at IS NULL
                                       AND occurred_at < CURRENT_TIMESTAMP - INTERVAL '7 days'))
                            ORDER BY COALESCE(completed_at, occurred_at) ASC LIMIT 500
                        )
                        """);
                int deadDeleted = statement.executeUpdate("""
                        DELETE FROM t_detection_event WHERE event_id IN (
                            SELECT event_id FROM t_detection_event
                            WHERE status = 'DEAD_LETTERED'
                              AND (dead_lettered_at < CURRENT_TIMESTAMP
                                   OR (dead_lettered_at IS NULL
                                       AND occurred_at < CURRENT_TIMESTAMP - INTERVAL '90 days'))
                            ORDER BY COALESCE(dead_lettered_at, occurred_at) ASC LIMIT 50
                        )
                        """);

                assertEquals(500, completedDeleted);
                assertEquals(50, deadDeleted);
                assertEquals(99_500, scalar(statement,
                        "SELECT COUNT(*) FROM t_detection_event WHERE status = 'COMPLETED'"));
                assertEquals(50, scalar(statement,
                        "SELECT COUNT(*) FROM t_detection_event WHERE status = 'DEAD_LETTERED'"));
                assertEquals(1_000, scalar(statement,
                        "SELECT COUNT(*) FROM t_detection_event WHERE status = 'PENDING'"));
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
