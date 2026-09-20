package com.socp.search.config;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Duration;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL evidence for upgrading a populated pre-outbox-lifecycle database. */
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SearchConfigPostgresMigrationTest {

    @Test
    void populatedV5UpgradeUsesTheDueIndexAndSupportsBoundedCleanup() throws Exception {
        try (GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
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


    @Test
    void retentionDeleteUsesActualRepositorySqlSkipsLockedRowsAndProtectsUnresolvedOutbox() throws Exception {
        try (GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
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
                    .load()
                    .migrate();

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO t_search_event
                            (id, event_id, timestamp, source, host, severity, msg,
                             tenant_id, created_at, updated_at)
                        SELECT 'eligible-' || n, 'eligible-event-' || n, CURRENT_TIMESTAMP,
                               'auth', 'host', 'INFO', 'eligible',
                               'tenant-a',
                               CURRENT_TIMESTAMP - INTERVAL '60 days' + (n * INTERVAL '1 minute'),
                               CURRENT_TIMESTAMP
                        FROM generate_series(1, 6) AS n
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_search_event
                            (id, event_id, timestamp, source, host, severity, msg,
                             tenant_id, created_at, updated_at)
                        VALUES
                            ('protected-pending', 'protected-event-pending', CURRENT_TIMESTAMP,
                             'auth', 'host', 'INFO', 'protected', 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '70 days', CURRENT_TIMESTAMP),
                            ('protected-processing', 'protected-event-processing', CURRENT_TIMESTAMP,
                             'auth', 'host', 'INFO', 'protected', 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '69 days', CURRENT_TIMESTAMP),
                            ('protected-dead', 'protected-event-dead', CURRENT_TIMESTAMP,
                             'auth', 'host', 'INFO', 'protected', 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '68 days', CURRENT_TIMESTAMP)
                        """);
                statement.executeUpdate("""
                        INSERT INTO t_ingestion_outbox
                            (id, event_id, routing_key, payload, status, next_attempt_at,
                             tenant_id, created_at, updated_at)
                        VALUES
                            ('outbox-pending', 'protected-event-pending', 'tenant-a|host|1',
                             '{}', 'PENDING', CURRENT_TIMESTAMP, 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '70 days', CURRENT_TIMESTAMP),
                            ('outbox-processing', 'protected-event-processing', 'tenant-a|host|2',
                             '{}', 'PROCESSING', CURRENT_TIMESTAMP, 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '69 days', CURRENT_TIMESTAMP),
                            ('outbox-dead', 'protected-event-dead', 'tenant-a|host|3',
                             '{}', 'DEAD', CURRENT_TIMESTAMP, 'tenant-a',
                             CURRENT_TIMESTAMP - INTERVAL '68 days', CURRENT_TIMESTAMP)
                        """);
            }

            String repositorySql = SearchEventRepository.class
                    .getMethod("deleteRetainedBatchBefore", java.time.Instant.class, int.class)
                    .getAnnotation(Query.class)
                    .value()
                    .replace(":cutoff", "CURRENT_TIMESTAMP - INTERVAL '30 days'")
                    .replace(":batchSize", "2");

            try (var locker = DriverManager.getConnection(url, "socp", "socp-test");
                 var cleaner = DriverManager.getConnection(url, "socp", "socp-test")) {
                locker.setAutoCommit(false);
                try (var lock = locker.createStatement()) {
                    lock.executeQuery("""
                            SELECT id FROM t_search_event
                            WHERE id = 'eligible-1'
                            FOR UPDATE
                            """).close();
                }

                cleaner.setAutoCommit(false);
                try (var cleanup = cleaner.createStatement()) {
                    cleanup.execute("SET LOCAL lock_timeout = '500ms'");
                    long started = System.nanoTime();
                    int removed = cleanup.executeUpdate(repositorySql);
                    long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    assertEquals(2, removed);
                    assertTrue(elapsedMs < 2_000L,
                            "SKIP LOCKED cleanup should not wait for another instance's row lock");
                }
                cleaner.commit();

                try (var verify = cleaner.createStatement();
                     var rows = verify.executeQuery("""
                             SELECT COUNT(*) FROM t_search_event WHERE id = 'eligible-1'
                             """)) {
                    rows.next();
                    assertEquals(1, rows.getInt(1), "locked row must be skipped, not deleted");
                }
                locker.rollback();
            }

            try (var connection = DriverManager.getConnection(url, "socp", "socp-test");
                 var cleanup = connection.createStatement()) {
                int removed;
                int removedAfterUnlock = 0;
                do {
                    removed = cleanup.executeUpdate(repositorySql);
                    removedAfterUnlock += removed;
                } while (removed > 0);
                assertEquals(4, removedAfterUnlock,
                        "the unlocked remainder should be drained in bounded batches");

                try (var rows = cleanup.executeQuery("""
                        SELECT COUNT(*) FROM t_search_event
                        WHERE id LIKE 'protected-%'
                        """)) {
                    rows.next();
                    assertEquals(3, rows.getInt(1),
                            "PENDING/PROCESSING/DEAD outbox rows must protect source events");
                }
                try (var rows = cleanup.executeQuery("""
                        SELECT COUNT(*) FROM t_search_event
                        WHERE id LIKE 'eligible-%'
                        """)) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
    }
}
