package com.socp.search.config.persistence.repository;

import com.socp.platform.test.MiddlewareImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.jpa.repository.Query;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IngestionOutboxClaimPostgresTest extends IngestionOutboxClaimPersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
            .withEnv("POSTGRES_DB", "outbox").withEnv("POSTGRES_USER", "socp").withEnv("POSTGRES_PASSWORD", "socp-test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/outbox";
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IngestionOutboxClaimPostgresTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
    }

    @Test void migrationPreservesExistingClaims() {
        Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                .schemas("claim_upgrade").locations("classpath:db/migration").target("11").load().migrate();
        jdbc.update("insert into claim_upgrade.t_ingestion_outbox "
                + "(id, tenant_id, event_id, routing_key, payload, status, attempts, next_attempt_at, created_at, updated_at) "
                + "values ('upgrade-id', 'tenant-a', 'upgrade-event', 'tenant-a|host|sample', '{}', 'PROCESSING', 3, now(), now(), now())");

        Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                .schemas("claim_upgrade").locations("classpath:db/migration").load().migrate();

        var stored = jdbc.queryForMap("select * from claim_upgrade.t_ingestion_outbox where id = 'upgrade-id'");
        assertEquals("PROCESSING", stored.get("status"));
        assertEquals(3, stored.get("attempts"));
        assertEquals("tenant-a", stored.get("tenant_id"));
        assertNull(stored.get("claim_token"));
    }

    @Test void hotKeyBatchStaysWithinAdmissionBudgetAfterPublishedHistoryAccumulates() throws Exception {
        jdbc.update("""
                insert into t_ingestion_outbox
                    (id, tenant_id, event_id, routing_key, payload, status, attempts,
                     next_attempt_at, created_at, updated_at)
                select 'history-' || n, 'tenant-a', 'history-' || n, 'tenant-a|host|hot', '{}',
                       case when n <= 10000 then 'PUBLISHED' else 'PENDING' end, 0,
                       timestamp '2026-09-01 00:00:00', timestamp '2026-09-01 00:00:00',
                       timestamp '2026-09-01 00:00:00'
                from generate_series(1, 14000) as n order by n
                """);
        // Model retained, vacuumed history rather than a just-created heap;
        // visibility information lets PostgreSQL cost the covering index.
        jdbc.execute("VACUUM (ANALYZE) t_ingestion_outbox");
        String sql = IngestionOutboxRepository.class
                .getMethod("findDueOrderedBatch", java.time.Instant.class)
                .getAnnotation(Query.class).value()
                .replace(":now", "timestamp '2026-09-01 00:00:00'");
        String plan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                (row, index) -> row.getString(1)));
        assertTrue(jdbc.queryForObject("select indisvalid from pg_index where indexrelid = "
                + "'public.idx_ingestion_outbox_state_key_sequence'::regclass", Boolean.class));
        // PostgreSQL can legitimately prefer a bitmap scan for this data shape.
        // Check the delivery admission requirement, not one optimizer choice.
        var execution = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
        assertTrue(execution.find(), plan);
        assertTrue(Double.parseDouble(execution.group(1)) < 2_000, plan);

        String headSql = IngestionOutboxRepository.class
                .getMethod("findDueHeadsForUpdate", java.time.Instant.class, int.class, int.class)
                .getAnnotation(Query.class).value()
                .replace(":now", "timestamp '2026-09-01 00:00:00'")
                .replace(":maxAttempts", "12").replace(":limit", "32");
        String headPlan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + headSql,
                (row, index) -> row.getString(1)));
        var headExecution = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(headPlan);
        assertTrue(headExecution.find(), headPlan);
        assertTrue(Double.parseDouble(headExecution.group(1)) < 2_000, headPlan);

        var heads = repository.findDueOrderedBatch(BASE);
        assertEquals(16, heads.size());
        assertEquals("history-10001", heads.getFirst().getId());
        assertEquals("history-10016", heads.getLast().getId());
        assertEquals(0, repository.claim("history-10002", BASE, 12, 0, "cannot-overtake"));
        assertEquals(1, repository.claim("history-10001", BASE, 12, 0, "head-owner"));
        assertEquals(1, repository.markPublished("history-10001", BASE, "head-owner"));
        assertEquals("history-10002", repository.findDueOrderedBatch(BASE).getFirst().getId());
    }

    @Test void deepMultiKeyBacklogKeepsHeadSelectionWithinAdmissionBudget() throws Exception {
        // Keep fixture inserts under the same five-second statement timeout.
        for (int start = 1; start <= 200000; start += 10000) jdbc.update("""
                insert into t_ingestion_outbox
                    (id, tenant_id, event_id, routing_key, payload, status, attempts,
                     next_attempt_at, created_at, updated_at)
                select 'deep-' || n, 'tenant-a', 'deep-' || n, 'tenant-a|host|' || (n % 32),
                       repeat(md5(n::text), 32), case when n <= 100000 then 'PUBLISHED' else 'PENDING' end,
                       0, timestamp '2026-09-01 00:00:00', timestamp '2026-09-01 00:00:00',
                       timestamp '2026-09-01 00:00:00'
                from generate_series(?, ?) as n order by n
                """, start, start + 9999);
        jdbc.execute("VACUUM (ANALYZE) t_ingestion_outbox");
        String sql = IngestionOutboxRepository.class
                .getMethod("findDueHeadsForUpdate", java.time.Instant.class, int.class, int.class)
                .getAnnotation(Query.class).value()
                .replace(":now", "timestamp '2026-09-01 00:00:00'")
                .replace(":maxAttempts", "12").replace(":limit", "32");
        System.out.println("Deep outbox estimated plan:\n" + String.join("\n", jdbc.query("EXPLAIN " + sql,
                (row, index) -> row.getString(1))));
        String plan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                (row, index) -> row.getString(1)));
        System.out.println("Deep outbox head-selection plan:\n" + plan);
        var elapsed = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
        assertTrue(elapsed.find(), plan);
        assertTrue(Double.parseDouble(elapsed.group(1)) < 2_000, plan);
        try (var ignored = com.socp.platform.tenant.context.TenantContext.openSystem()) {
            var first = batchStore.claim(32, 12);
            assertEquals(32, first.rows().size());
            assertEquals(java.util.stream.IntStream.rangeClosed(100001, 100032)
                    .mapToObj(n -> "deep-" + n).collect(java.util.stream.Collectors.toSet()),
                    first.rows().stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(batchStore.claim(32, 12).rows().isEmpty(), "processing heads must block every successor");
            var ids = first.rows().stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet());
            assertEquals(32, batchStore.settle(first, ids, 12).published());
            var next = batchStore.claim(32, 12);
            assertEquals(java.util.stream.IntStream.rangeClosed(100033, 100064)
                    .mapToObj(n -> "deep-" + n).collect(java.util.stream.Collectors.toSet()),
                    next.rows().stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet()));
        }
    }
}
