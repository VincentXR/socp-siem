package com.socp.search.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL evidence for upgrading a populated pre-outbox-lifecycle database. */
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SearchConfigPostgresMigrationTest {

    @Test
    void populatedV5UpgradeUsesTheDueIndexAndSupportsBoundedCleanup() throws Exception {
        try (GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withEnv("POSTGRES_DB", "search")
                .withEnv("POSTGRES_USER", "socp")
                .withEnv("POSTGRES_PASSWORD", "socp-test")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2))) {
            postgres.start();
            String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/search";
            Flyway.configure()
                    .dataSource(url, "socp", "socp-test")
                    .locations("classpath:db/migration")
                    .target("5")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_ingestion_outbox
                            (id, event_id, routing_key, payload, status, published_at,
                             tenant_id, created_at, updated_at)
                        SELECT 'published-' || n, 'event-' || n, 'tenant-a|host|' || n,
                               '{}', 'PUBLISHED', CURRENT_TIMESTAMP - INTERVAL '40 days',
                               'tenant-a', CURRENT_TIMESTAMP - INTERVAL '40 days', CURRENT_TIMESTAMP
                        FROM generate_series(1, 100000) AS n
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_ingestion_outbox
                            (id, event_id, routing_key, payload, status,
                             tenant_id, created_at, updated_at)
                        SELECT 'pending-' || n, 'pending-event-' || n, 'tenant-a|host|' || n,
                               '{}', 'PENDING', 'tenant-a',
                               CURRENT_TIMESTAMP - INTERVAL '1 minute', CURRENT_TIMESTAMP
                        FROM generate_series(1, 1000) AS n
                        """);
            }

            Flyway.configure()
                    .dataSource(url, "socp", "socp-test")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.execute("ANALYZE t_ingestion_outbox");
                StringBuilder plan = new StringBuilder();
                try (var rows = statement.executeQuery("""
                        EXPLAIN (ANALYZE, BUFFERS)
                        SELECT * FROM t_ingestion_outbox
                        WHERE status = 'PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                        ORDER BY next_attempt_at ASC, created_at ASC
                        LIMIT 200
                        """)) {
                    while (rows.next()) plan.append(rows.getString(1)).append('\n');
                }
                String explain = plan.toString();
                assertTrue(explain.contains("idx_ingestion_outbox_due_v2"), explain);
                assertFalse(explain.contains("Seq Scan on t_ingestion_outbox"), explain);
                assertFalse(explain.contains("Sort  ("), explain);

                int deleted = statement.executeUpdate("""
                        DELETE FROM t_ingestion_outbox WHERE id IN (
                            SELECT id FROM t_ingestion_outbox
                            WHERE status = 'PUBLISHED' AND published_at < CURRENT_TIMESTAMP
                            ORDER BY published_at ASC LIMIT 250
                        )
                        """);
                assertEquals(250, deleted);
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM t_ingestion_outbox WHERE status = 'PUBLISHED'
                        """)) {
                    rows.next();
                    assertEquals(99_750, rows.getInt(1));
                }
            }
        }
    }
}
