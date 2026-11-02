package com.socp.search.config.persistence.repository;

import com.socp.platform.test.MiddlewareImages;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.service.IngestionOutboxBatchService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IngestionOutboxClaimPostgresTest extends IngestionOutboxClaimPersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
            .withEnv("POSTGRES_DB", "outbox").withEnv("POSTGRES_USER", "socp").withEnv("POSTGRES_PASSWORD", "socp-test")
            .withExposedPorts(5432)
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))))
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

    @Test void competingPublishersBatchRepeatedKeysWithoutOvertakingOrDuplicatePublication() throws Exception {
        jdbc.execute("""
                insert into t_ingestion_outbox
                    (id, tenant_id, event_id, routing_key, payload, status, attempts,
                     next_attempt_at, created_at, updated_at)
                select 'wave-' || n, 'tenant-a', 'wave-' || n, 'key-' || (n % 32), n::text,
                       'PENDING', 0, timestamp '2026-09-01 00:00:00',
                       timestamp '2026-09-01 00:00:00', timestamp '2026-09-01 00:00:00'
                from generate_series(1, 96) as fixture(n) order by fixture.n
                """);
        var competingRepository = org.mockito.Mockito.mock(IngestionOutboxRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(repository));
        var selections = new java.util.concurrent.atomic.AtomicInteger();
        var bothSelected = new java.util.concurrent.CountDownLatch(2);
        org.mockito.Mockito.doAnswer(invocation -> {
            var selected = repository.findDueOrderedBatch(invocation.getArgument(0));
            if (selections.incrementAndGet() <= 2) {
                assertEquals(96, selected.size());
                bothSelected.countDown();
                assertTrue(bothSelected.await(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            return selected;
        }).when(competingRepository).findDueOrderedBatch(org.mockito.ArgumentMatchers.any());
        var sent = java.util.concurrent.ConcurrentHashMap.<Integer>newKeySet();
        // The transport API is package-private. Keep the production boundary
        // intact while exercising its real repository and transactional batch service.
        var producer = org.mockito.Mockito.mock(com.socp.search.config.infrastructure.kafka.KafkaEventProducer.class, invocation -> {
            if (invocation.getMethod().getName().equals("isEnabled")) return true;
            if (invocation.getMethod().getName().equals("sendAndAwait")) {
                throw new AssertionError("repeated-key backlogs must use batch publication");
            }
            if (!invocation.getMethod().getName().equals("sendAsync")) {
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            }
            assertEquals("tenant-a", TenantContext.require());
            int position = Integer.parseInt(invocation.getArgument(1));
            if (position > 32) {
                assertTrue(sent.contains(position - 32), "the earlier send has already returned its successful receipt");
                assertEquals("PROCESSING", jdbc.queryForObject(
                        "select status from t_ingestion_outbox where id=?", String.class, "wave-" + (position - 32)),
                        "one durable prefix owns the key without a database commit between every send");
            }
            assertTrue(sent.add(position), "competing claims must not publish an event twice");
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        });
        var publishers = java.util.stream.IntStream.range(0, 2).mapToObj(i -> {
            var publisher = new com.socp.search.config.infrastructure.kafka.IngestionOutboxPublisher(
                    competingRepository, producer, null, 8, 12, 60_000, 100, 1, 4, 10_000);
            ReflectionTestUtils.setField(publisher, "batchService", batches);
            return publisher;
        }).toList();
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var drains = publishers.stream().map(p -> workers.submit(() -> TenantContext.runAsSystem(p::publish))).toList();
            for (var drain : drains) drain.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            publishers.forEach(p -> ReflectionTestUtils.invokeMethod(p, "stop"));
        }
        assertEquals(96, sent.size());
        assertEquals(96, repository.countByStatus("PUBLISHED"));
        assertEquals(0, repository.countByStatus("PROCESSING"));
        assertEquals(0, repository.countByStatus("PENDING"));
        assertEquals(96, jdbc.queryForObject("select count(*) from t_ingestion_outbox where attempts=1", Integer.class));
        org.mockito.Mockito.verify(competingRepository, org.mockito.Mockito.never()).claim(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test void fencedCompletionUsesIdentityIndexWhenStatusStatisticsAreStale() throws Exception {
        jdbc.execute("insert into t_ingestion_outbox "
                + "(id, tenant_id, event_id, routing_key, payload, status, attempts, next_attempt_at, created_at, updated_at) "
                + "select 'old-'||i, 'tenant-a', 'old-'||i, 'key-'||i, '{}', 'PUBLISHED', 1, now(), now(), now() "
                + "from generate_series(1, 5000) i");
        jdbc.execute("analyze t_ingestion_outbox");
        jdbc.execute("insert into t_ingestion_outbox "
                + "(id, tenant_id, event_id, routing_key, payload, status, attempts, claim_token, processing_key, "
                + "next_attempt_at, created_at, updated_at) "
                + "select 'active-'||i, 'tenant-a', 'active-'||i, 'active-key-'||i, '{}', 'PROCESSING', 1, 'owner', "
                + "'tenant-a:active-key-'||i, now(), now(), now() from generate_series(1, 3000) i");
        // Explain the repository's predicate against the skewed real table.
        // This catches the old plan that used status instead of the unique ID.
        String query = IngestionOutboxRepository.class.getMethod("markPublished", String.class,
                java.time.Instant.class, String.class).getAnnotation(Query.class).value()
                .replace("IngestionOutboxEvent", "t_ingestion_outbox").replace("e.", "")
                .replace("claimToken", "claim_token").replace("processingKey", "processing_key")
                .replace("claimedAt", "claimed_at").replace("publishedAt", "published_at")
                .replace("lastError", "last_error").replace("updatedAt", "updated_at")
                .replace(":now", "now()").replace(":id", "'active-3000'").replace(":token", "'owner'");
        String plan = String.join("\n", jdbc.queryForList("explain " + query, String.class));
        assertTrue(plan.contains("pk_t_ingestion_outbox"), plan);
        assertEquals(0, repository.markPublished("active-3000", BASE, "stale-owner"));
        assertEquals(1, repository.markPublished("active-3000", BASE, "owner"));
        assertEquals(2999, repository.countByStatus("PROCESSING"));
        assertEquals(0, repository.markPublished("active-3000", BASE, "owner"));
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

    @Test void boundedSelectionIndexUpgradePreservesPendingAndProcessingReceipts() {
        Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                .schemas("selector_upgrade").locations("classpath:db/migration").target("17").load().migrate();
        try {
            jdbc.update("""
                    insert into selector_upgrade.t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts, next_attempt_at,
                         created_at, updated_at, sequence_no, claim_token, processing_key, claimed_at)
                    values
                        ('pending', 'tenant-a', 'pending', 'pending-key', '{"keep":"pending"}', 'PENDING', 4,
                         timestamp '2026-09-01 00:01:00', timestamp '2026-09-01', timestamp '2026-09-01',
                         501, null, null, null),
                        ('processing', 'tenant-a', 'processing', 'active-key', '{"keep":"active"}', 'PROCESSING', 3,
                         timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01',
                         502, 'active-owner', 'tenant-a:active-key', timestamp '2026-09-01'),
                        ('legacy', 'tenant-a', 'legacy', 'legacy-key', '{"keep":"legacy"}', 'PROCESSING', 2,
                         timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01',
                         503, 'legacy-owner', null, timestamp '2026-09-01')
                    """);
            var before = jdbc.queryForList("select * from selector_upgrade.t_ingestion_outbox order by id");

            Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                    .schemas("selector_upgrade").locations("classpath:db/migration").target("18").load().migrate();

            assertEquals(before, jdbc.queryForList("select * from selector_upgrade.t_ingestion_outbox order by id"));
            for (String index : List.of("idx_ingestion_outbox_due_sequence", "idx_ingestion_outbox_state_key_order")) {
                assertTrue(jdbc.queryForObject("select indisvalid and indisready from pg_index "
                        + "where indexrelid = ?::regclass", Boolean.class, "selector_upgrade." + index));
            }
            var definitions = jdbc.queryForList("select indexdef from pg_indexes where schemaname='selector_upgrade' "
                    + "and indexname in ('idx_ingestion_outbox_due_sequence','idx_ingestion_outbox_state_key_order')", String.class);
            assertTrue(definitions.stream().anyMatch(definition -> definition.contains("(status, next_attempt_at, sequence_no, id)")));
            assertTrue(definitions.stream().anyMatch(definition -> definition.contains("(status, tenant_id, routing_key, sequence_no, id, next_attempt_at)")));
            assertEquals(0, jdbc.queryForObject("select count(*) from pg_indexes where schemaname='selector_upgrade' "
                    + "and indexname in ('idx_ingestion_outbox_due_v2','idx_ingestion_outbox_state_key_sequence')", Integer.class));

            Integer priorIndexCount = jdbc.queryForObject("select count(*) from pg_indexes "
                    + "where schemaname='selector_upgrade' and tablename='t_ingestion_outbox'", Integer.class);
            Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                    .schemas("selector_upgrade").locations("classpath:db/migration").target("19").load().migrate();

            assertEquals(before, jdbc.queryForList("select * from selector_upgrade.t_ingestion_outbox order by id"));
            assertTrue(jdbc.queryForObject("select indisvalid and indisready from pg_index "
                    + "where indexrelid='selector_upgrade.idx_ingestion_outbox_due_covering'::regclass", Boolean.class));
            String coveringDefinition = jdbc.queryForObject("select indexdef from pg_indexes "
                    + "where schemaname='selector_upgrade' and indexname='idx_ingestion_outbox_due_covering'", String.class);
            assertTrue(coveringDefinition.contains("(status, next_attempt_at, sequence_no, id, tenant_id, routing_key)"));
            assertEquals(0, jdbc.queryForObject("select count(*) from pg_indexes where schemaname='selector_upgrade' "
                    + "and indexname='idx_ingestion_outbox_due_sequence'", Integer.class));
            assertEquals(priorIndexCount, jdbc.queryForObject("select count(*) from pg_indexes "
                    + "where schemaname='selector_upgrade' and tablename='t_ingestion_outbox'", Integer.class));

            Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                    .schemas("selector_upgrade").locations("classpath:db/migration").target("20").load().migrate();

            assertEquals(before, jdbc.queryForList("select * from selector_upgrade.t_ingestion_outbox order by id"));
            assertEquals(0, jdbc.queryForObject("select count(*) from pg_indexes "
                    + "where schemaname='selector_upgrade' and indexname='idx_ingestion_outbox_key_sequence'", Integer.class));
            assertTrue(jdbc.queryForObject("select indisvalid and indisready from pg_index "
                    + "where indexrelid='selector_upgrade.idx_ingestion_outbox_state_key_order'::regclass", Boolean.class));
            assertEquals(priorIndexCount - 1, jdbc.queryForObject("select count(*) from pg_indexes "
                    + "where schemaname='selector_upgrade' and tablename='t_ingestion_outbox'", Integer.class));
        } finally {
            jdbc.execute("drop schema selector_upgrade cascade");
        }
    }

    @Test void newTenantClaimsAndCompletionStayOnIdentityAndKeyIndexesWhileStatisticsLag() throws Exception {
        jdbc.execute("ALTER TABLE t_ingestion_outbox SET (autovacuum_enabled = false)");
        try (var scope = TenantContext.open("tenant-new")) {
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'history-' || n, 'retained-' || (n % 20), 'history-' || n, 'history-key-' || n,
                           repeat('x', 450), 'PUBLISHED', 1,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 12000) as n
                    """);
            jdbc.execute("ANALYZE t_ingestion_outbox");
            // Both this tenant and active statuses are absent from the retained
            // statistics, as happens when a fresh tenant starts a burst.
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'pending-' || n, 'tenant-new', 'pending-' || n, 'pending-key-' || n,
                           repeat('x', 450), 'PENDING', 0,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 4000) as n
                    """);
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts, claim_token,
                         processing_key, next_attempt_at, created_at, updated_at)
                    select 'active-' || n, 'tenant-new', 'active-' || n, 'active-key-' || n,
                           repeat('x', 450), 'PROCESSING', 1, 'active-owner', 'tenant-new:active-key-' || n,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 2000) as n
                    """);
            String batchClaimSql = (String) ReflectionTestUtils.getField(IngestionOutboxBatchService.class, "CLAIM_SQL");
            String batchClaimPlan = String.join("\n", jdbc.queryForList("EXPLAIN " + batchClaimSql, String.class,
                    "batch-owner", java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE),
                    "pending-1", "tenant-new", 0, 12, java.sql.Timestamp.from(BASE)));
            assertIdentityAndKeyLookups(batchClaimPlan);

            String singletonSql = repositorySql("claim", String.class, java.time.Instant.class,
                    int.class, int.class, String.class)
                    .replace(":token", "'single-owner'").replace(":id", "'pending-33'")
                    .replace(":now", "timestamp '2026-09-01'")
                    .replace(":maxAttempts", "12").replace(":expectedAttempts", "0");
            assertIdentityAndKeyLookups(String.join("\n", jdbc.queryForList("EXPLAIN " + singletonSql, String.class)));

            var ids = java.util.stream.IntStream.rangeClosed(1, 32).mapToObj(n -> "active-" + n).toList();
            String batchCompletionSql = repositorySql("markPublishedBatch", String.class,
                    java.util.Collection.class, java.time.Instant.class, String.class)
                    .replace(":tenant_id", "'tenant-new'")
                    .replace(":ids", "('" + String.join("','", ids) + "')")
                    .replace(":now", "timestamp '2026-09-01'").replace(":token", "'active-owner'");
            String completionPlan = String.join("\n", jdbc.queryForList("EXPLAIN " + batchCompletionSql, String.class));
            assertTrue(completionPlan.contains("pk_t_ingestion_outbox"), completionPlan);
            assertTrue(completionPlan.lines().anyMatch(line -> line.contains("Index Cond:")
                    && line.contains("(id)") && line.contains("ANY")), completionPlan);

            var candidates = java.util.stream.IntStream.rangeClosed(1, 32)
                    .mapToObj(n -> repository.findByIdAndTenantId("pending-" + n, "tenant-new").orElseThrow()).toList();
            assertEquals(32, batches.claimIndependent(candidates, "batch-owner", 12, BASE).size());
            assertEquals(1, repository.claim("pending-33", BASE, 12, 0, "single-owner"));
            assertEquals(0, repository.markPublishedBatch("tenant-new", ids, BASE, "stale-owner"));
            assertEquals(32, repository.markPublishedBatch("tenant-new", ids, BASE, "active-owner"));
            assertEquals(0, repository.markPublishedBatch("tenant-new", ids, BASE, "active-owner"));
            assertEquals(2001, repository.countByStatus("PROCESSING"));
        } finally {
            jdbc.execute("ALTER TABLE t_ingestion_outbox RESET (autovacuum_enabled)");
        }
    }

    private static void assertIdentityAndKeyLookups(String plan) {
        assertTrue(plan.contains("pk_t_ingestion_outbox"), plan);
        assertTrue(plan.lines().anyMatch(line -> line.contains("Index Cond:") && line.contains("(id)")), plan);
        assertEquals(2, plan.lines().filter(line -> line.contains("Index Cond:")
                && line.contains("tenant_id") && line.contains("routing_key")).count(), plan);
        assertTrue(plan.lines().anyMatch(line -> line.contains("Index Cond:")
                && line.contains("tenant_id") && line.contains("routing_key") && line.contains("sequence_no")), plan);
    }

    private static String repositorySql(String method, Class<?>... parameters) throws Exception {
        String sql = IngestionOutboxRepository.class.getMethod(method, parameters).getAnnotation(Query.class).value()
                .replace("IngestionOutboxEvent", "t_ingestion_outbox")
                .replace("tenantId", "tenant_id").replace("routingKey", "routing_key")
                .replace("claimToken", "claim_token").replace("processingKey", "processing_key")
                .replace("claimedAt", "claimed_at").replace("publishedAt", "published_at")
                .replace("lastError", "last_error").replace("updatedAt", "updated_at")
                .replace("nextAttemptAt", "next_attempt_at").replace("sequenceNo", "sequence_no");
        int where = sql.indexOf(" where ");
        return sql.substring(0, where).replace("e.", "") + sql.substring(where);
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
        vacuumFixture(true);
        String sql = IngestionOutboxRepository.class
                .getMethod("findDueOrderedBatch", java.time.Instant.class)
                .getAnnotation(Query.class).value()
                .replace(":now", "timestamp '2026-09-01 00:00:00'");
        String plan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                (row, index) -> row.getString(1)));
        assertTrue(jdbc.queryForObject("select indisvalid from pg_index where indexrelid = "
                + "'public.idx_ingestion_outbox_state_key_order'::regclass", Boolean.class));
        // PostgreSQL can legitimately prefer a bitmap scan for this data shape.
        // Check the delivery admission requirement, not one optimizer choice.
        var execution = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
        assertTrue(execution.find(), plan);
        assertTrue(Double.parseDouble(execution.group(1)) < 2_000, plan);

        var heads = repository.findDueOrderedBatch(BASE);
        assertEquals(16, heads.size());
        assertEquals("history-10001", heads.getFirst().getId());
        assertEquals("history-10016", heads.getLast().getId());
        assertEquals(0, repository.claim("history-10002", BASE, 12, 0, "cannot-overtake"));
        assertEquals(1, repository.claim("history-10001", BASE, 12, 0, "head-owner"));
        assertEquals(1, repository.markPublished("history-10001", BASE, "head-owner"));
        assertEquals("history-10002", repository.findDueOrderedBatch(BASE).getFirst().getId());
    }

    @Test void processingBlockerUsesScopedCoveringIndexWithStaleStatusStatistics() throws Exception {
        jdbc.execute("ALTER TABLE t_ingestion_outbox SET (autovacuum_enabled = false)");
        try {
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'retained-' || n, 'tenant-a', 'retained-' || n, 'hot-key',
                           repeat('x', 450), 'PUBLISHED', 0,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 10000) as n
                    """);
            jdbc.execute("ANALYZE t_ingestion_outbox");
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'pending-' || n, 'tenant-a', 'pending-' || n, 'hot-key', '{}', 'PENDING', 0,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 400) as n order by n
                    """);
            assertEquals(16, repository.findDueOrderedBatch(BASE).size());
            // Hibernate binds the two occurrences separately, including in a
            // server-side generic plan. Neither active status is in statistics.
            for (String plan : explainPreparedSelectorPlans()) {
                var blocker = java.util.regex.Pattern.compile(
                        "Index Only Scan using idx_ingestion_outbox_state_key_order on t_ingestion_outbox blocker[^\\n]*\\n\\s*Index Cond: ([^\\n]+)")
                        .matcher(plan);
                assertTrue(blocker.find(), plan);
                String condition = blocker.group(1);
                assertTrue(condition.contains("status") && condition.contains("tenant_id")
                        && condition.contains("routing_key"), condition);
            }
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    values ('legacy', 'tenant-a', 'legacy', 'hot-key', '{}', 'PROCESSING', 1,
                            timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01')
                    """);
            assertTrue(repository.findDueOrderedBatch(BASE).isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE t_ingestion_outbox RESET (autovacuum_enabled)");
        }
    }

    @Test void coldBurstWithStaleStatisticsDoesNotJoinEveryPendingPayload() throws Exception {
        jdbc.execute("ALTER TABLE t_ingestion_outbox SET (autovacuum_enabled = false)");
        try {
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'retained-' || n, 'tenant-a', 'retained-' || n, 'key-' || n,
                           repeat('x', 450), 'PUBLISHED', 0,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 25000) as n
                    """);
            jdbc.execute("ANALYZE t_ingestion_outbox");
            // Statistics still report an empty pending queue during this burst.
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'burst-' || n, 'tenant-a', 'burst-' || n, 'key-' || n,
                           repeat('x', 450), 'PENDING', 0,
                           timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                    from generate_series(1, 20000) as n order by n
                    """);
            // Visibility can be current while ANALYZE still reports no pending
            // rows. This made the old unordered covering index win before LIMIT.
            vacuumFixture(false);
            String sql = IngestionOutboxRepository.class
                    .getMethod("findDueOrderedBatch", java.time.Instant.class)
                    .getAnnotation(Query.class).value()
                    .replace(":now", "timestamp '2026-09-01'");
            String plan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                    (row, index) -> row.getString(1)));
            var execution = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
            assertTrue(execution.find(), plan);
            assertTrue(Double.parseDouble(execution.group(1)) < 2_000, plan);
            var payloadFetch = java.util.regex.Pattern.compile(
                    "on t_ingestion_outbox queued[^\\n]*actual[^\\n]*rows=([0-9.]+) loops=(\\d+)")
                    .matcher(plan);
            assertTrue(payloadFetch.find(), plan);
            assertTrue(Double.parseDouble(payloadFetch.group(1)) * Long.parseLong(payloadFetch.group(2)) <= 200, plan);
            for (String preparedPlan : explainPreparedSelectorPlans()) {
                // Head deduplication/sorting reads narrow covering-index entries;
                // the payload lookup remains behind the 200-row admission bound.
                assertTrue(preparedPlan.contains("Index Only Scan using idx_ingestion_outbox_state_key_order on t_ingestion_outbox"), preparedPlan);
                assertFalse(preparedPlan.contains("Seq Scan on t_ingestion_outbox"), preparedPlan);
                assertFalse(preparedPlan.contains("JIT:"), preparedPlan);
            }
            var batch = repository.findDueOrderedBatch(BASE);
            assertEquals(200, batch.size());
            assertEquals("burst-1", batch.getFirst().getId());
            assertEquals("burst-200", batch.getLast().getId());
        } finally {
            jdbc.execute("ALTER TABLE t_ingestion_outbox RESET (autovacuum_enabled)");
        }
    }

    private List<String> explainPreparedSelectorPlans() throws Exception {
        String sql = IngestionOutboxRepository.class
                .getMethod("findDueOrderedBatch", java.time.Instant.class)
                .getAnnotation(Query.class).value()
                .replaceFirst(":now", java.util.regex.Matcher.quoteReplacement("$1"))
                .replaceFirst(":now", java.util.regex.Matcher.quoteReplacement("$2"));
        var plans = new java.util.ArrayList<String>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("PREPARE selector_plan(timestamptz, timestamptz) AS " + sql);
                try {
                    for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
                        statement.execute("SET LOCAL plan_cache_mode = " + mode);
                        var plan = new StringBuilder(mode).append('\n');
                        try (var rows = statement.executeQuery("EXPLAIN EXECUTE selector_plan("
                                + "timestamp '2026-09-01', timestamp '2026-09-01')")) {
                            while (rows.next()) plan.append(rows.getString(1)).append('\n');
                        }
                        plans.add(plan.toString());
                    }
                } finally {
                    statement.execute("DEALLOCATE selector_plan");
                }
            } finally {
                connection.rollback();
            }
        }
        return plans;
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
        vacuumFixture(true);
        String sql = IngestionOutboxRepository.class
                .getMethod("findDueOrderedBatch", java.time.Instant.class)
                .getAnnotation(Query.class).value()
                .replace(":now", "timestamp '2026-09-01 00:00:00'");
        System.out.println("Deep outbox estimated plan:\n" + String.join("\n", jdbc.query("EXPLAIN " + sql,
                (row, index) -> row.getString(1))));
        String plan = String.join("\n", jdbc.query("EXPLAIN (ANALYZE, BUFFERS) " + sql,
                (row, index) -> row.getString(1)));
        System.out.println("Deep outbox head-selection plan:\n" + plan);
        var loops = java.util.regex.Pattern.compile("loops=(\\d+)").matcher(plan);
        long largestLoop = 0;
        while (loops.find()) largestLoop = Math.max(largestLoop, Long.parseLong(loops.group(1)));
        assertTrue(largestLoop <= 512,
                "head discovery must not repeat a key probe for every pending successor:\n" + plan);
        var elapsed = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
        assertTrue(elapsed.find(), plan);
        assertTrue(Double.parseDouble(elapsed.group(1)) < 2_000, plan);
        var selected = repository.findDueOrderedBatch(BASE);
        assertEquals(200, selected.size());
        assertEquals("deep-100001", selected.getFirst().getId());
        assertEquals(32, selected.subList(0, 32).stream().map(row -> row.getRoutingKey()).distinct().count(),
                "hot-key successors must not crowd independent heads out of the bounded batch");
        String claimSql = (String) ReflectionTestUtils.getField(IngestionOutboxBatchService.class, "CLAIM_SQL");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        String claimPlan = transaction.execute(status -> {
            status.setRollbackOnly();
            return String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + claimSql, String.class,
                    "plan-owner", java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE),
                    "deep-100001", "tenant-a", 0, 12, java.sql.Timestamp.from(BASE)));
        });
        System.out.println("Retained-history outbox claim plan:\n" + claimPlan);
        assertEquals(2, claimPlan.lines().filter(line -> line.contains("Index Cond:")
                && line.contains("tenant_id") && line.contains("routing_key") && line.contains("status")).count(),
                "both claim fences must probe active states without filtering retained key history:\n" + claimPlan);
        // Selection admits up to 16 positions per key. Claim a bounded set of
        // its fixture's heads independently to verify all 32 predecessor fences.
        var firstIds = java.util.stream.IntStream.rangeClosed(100001, 100032)
                .mapToObj(n -> "deep-" + n).toList();
        var first = batches.claimIndependent(repository.findByTenantIdAndEventIdIn("tenant-a", firstIds),
                "first-owner", 12, BASE);
        assertEquals(32, first.size());
        assertEquals(new java.util.HashSet<>(firstIds),
                first.stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(repository.findDueOrderedBatch(BASE).isEmpty(), "processing heads must block every successor");
        String followerSql = ReflectionTestUtils.invokeMethod(IngestionOutboxBatchService.class, "followerClaimSql", 1, 1);
        String followerPlan = transaction.execute(status -> {
            status.setRollbackOnly();
            return String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + followerSql, String.class,
                    "first-owner", java.sql.Timestamp.from(BASE), java.sql.Timestamp.from(BASE),
                    "deep-100033", "tenant-a", 12, java.sql.Timestamp.from(BASE),
                    "deep-100001", "first-owner", "first-owner", "deep-100033"));
        });
        System.out.println("Deep outbox follower-claim plan:\n" + followerPlan);
        assertTrue(followerPlan.contains("pk_t_ingestion_outbox"), followerPlan);
        assertTrue(followerPlan.contains("ux_ingestion_outbox_processing_key"),
                "the anchor must use its unique lease lookup rather than scanning tenant/status:\n" + followerPlan);
        assertEquals(32, batches.markAcknowledged(first, "first-owner", BASE));
        assertEquals("deep-100033", repository.findDueOrderedBatch(BASE).getFirst().getId());
        var nextIds = java.util.stream.IntStream.rangeClosed(100033, 100064)
                .mapToObj(n -> "deep-" + n).toList();
        var next = batches.claimIndependent(repository.findByTenantIdAndEventIdIn("tenant-a", nextIds),
                "next-owner", 12, BASE);
        assertEquals(new java.util.HashSet<>(nextIds),
                next.stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(32, batches.markAcknowledged(next, "next-owner", BASE));
        var prefix = repository.findDueOrderedBatch(BASE);
        assertEquals(200, prefix.size());
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");
        assertEquals(200, batches.claimOrderedPrefixes(prefix, "bulk-prefix-owner", 12, BASE).size());
        assertEquals(1L, jdbc.queryForObject("select coalesce(sum(calls), 0) from pg_stat_statements "
                        + "where query like ? and query like ?", Long.class,
                "update t_ingestion_outbox e set status=%", "%from t_ingestion_outbox anchor%"),
                "a prefix must execute one server-side follower update, not one per row");
        assertEquals(168L, jdbc.queryForObject("select coalesce(sum(rows), 0) from pg_stat_statements "
                        + "where query like ? and query like ?", Long.class,
                "update t_ingestion_outbox e set status=%", "%from t_ingestion_outbox anchor%"));
    }

    @Test void staleStatusStatisticsCannotTurnPrefixClaimsIntoHistoryScans() throws Exception {
        // Retain update churn and stale all-PUBLISHED statistics while a new
        // pending burst arrives. A vacuumed, freshly analyzed table hid the
        // old key/sequence index's repeated retained-history scans.
        vacuumFixture(false);
        jdbc.execute("ALTER TABLE t_ingestion_outbox SET (autovacuum_enabled=false)");
        try {
            for (int start = 1; start <= 20000; start += 5000) {
                jdbc.update("""
                        insert into t_ingestion_outbox
                            (id, tenant_id, event_id, routing_key, payload, status, attempts,
                             next_attempt_at, created_at, updated_at)
                        select 'history-' || n, 'tenant-a', 'history-' || n,
                               'tenant-a|host|load-20261102-b-' || (n % 32),
                               (select string_agg(md5(n::text || ':' || k::text), '') from generate_series(1,32) k),
                               'PENDING', 0, timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                        from generate_series(?, ?) n order by n
                        """, start, start + 4999);
                jdbc.update("update t_ingestion_outbox set status='PROCESSING', attempts=1 where id in (select 'history-' || n from generate_series(?, ?) n)", start, start + 4999);
                jdbc.update("update t_ingestion_outbox set status='PUBLISHED', published_at=timestamp '2026-09-01' where id in (select 'history-' || n from generate_series(?, ?) n)", start, start + 4999);
            }
            jdbc.execute("ANALYZE t_ingestion_outbox");
            for (int start = 1; start <= 5000; start += 5000) {
                jdbc.update("""
                        insert into t_ingestion_outbox
                            (id, tenant_id, event_id, routing_key, payload, status, attempts,
                             next_attempt_at, created_at, updated_at)
                        select 'burst-' || n, 'tenant-a', 'burst-' || n,
                               'tenant-a|host|load-20261102-b-' || (n % 32),
                               (select string_agg(md5(n::text || ':' || k::text), '') from generate_series(1,32) k),
                               'PENDING', 0, timestamp '2026-09-01', timestamp '2026-09-01', timestamp '2026-09-01'
                        from generate_series(?, ?) n order by n
                        """, start, start + 4999);
            }
            String selector = IngestionOutboxRepository.class.getMethod("findDueOrderedBatch", java.time.Instant.class)
                    .getAnnotation(Query.class).value().replace(":now", "timestamp '2026-09-01'");
            String selectorPlan = String.join("\n", jdbc.queryForList("EXPLAIN " + selector, String.class));
            assertEquals(3, selectorPlan.lines().filter(line -> line.contains("Index Cond:")
                    && line.contains("status") && line.contains("tenant_id") && line.contains("routing_key")).count(),
                    "key membership, cutoff and ownership probes must bound active states:\n" + selectorPlan);
            var selected = repository.findDueOrderedBatch(BASE);
            var heads = new java.util.LinkedHashMap<String, com.socp.search.config.domain.IngestionOutboxEvent>();
            selected.forEach(row -> heads.putIfAbsent(row.getRoutingKey(), row));
            var claimed = batches.claimIndependent(List.copyOf(heads.values()), "first-owner", 12, BASE);
            assertEquals(32, claimed.size());
            var ids = claimed.stream().map(row -> row.getId()).collect(java.util.stream.Collectors.toSet());
            var followers = selected.stream().filter(row -> !ids.contains(row.getId())).toList();
            for (int size : List.of(1, 10, followers.size())) {
                for (String plan : explainPreparedFollowerPlans(followers.subList(0, size), claimed)) {
                    System.out.println("Churned stale-status follower plan size=" + size + "\n" + plan);
                    assertEquals(2, plan.lines().filter(line -> line.contains("Index Cond:")
                            && line.contains("status") && line.contains("tenant_id") && line.contains("routing_key")).count(),
                            "both predecessor fences must bound active states before reading history:\n" + plan);
                    var duration = java.util.regex.Pattern.compile("Execution Time: ([0-9.]+) ms").matcher(plan);
                    assertTrue(duration.find(), plan);
                    assertTrue(Double.parseDouble(duration.group(1)) < 2_000, plan);
                }
            }
        } finally {
            jdbc.execute("ALTER TABLE t_ingestion_outbox RESET (autovacuum_enabled)");
        }
    }

    private List<String> explainPreparedFollowerPlans(
            List<com.socp.search.config.domain.IngestionOutboxEvent> followers,
            List<com.socp.search.config.domain.IngestionOutboxEvent> heads) throws Exception {
        String sql = ReflectionTestUtils.invokeMethod(IngestionOutboxBatchService.class,
                "followerClaimSql", followers.size(), heads.size());
        var values = new java.util.ArrayList<String>();
        values.add("'first-owner'");
        values.add("timestamp '2026-09-01'");
        values.add("timestamp '2026-09-01'");
        followers.forEach(row -> values.add("'" + row.getId().replace("'", "''") + "'"));
        values.add("'tenant-a'");
        values.add("12");
        values.add("timestamp '2026-09-01'");
        heads.forEach(row -> values.add("'" + row.getId().replace("'", "''") + "'"));
        values.add("'first-owner'");
        values.add("'first-owner'");
        followers.forEach(row -> values.add("'" + row.getId().replace("'", "''") + "'"));
        for (int i = 1; i <= values.size(); i++) {
            sql = sql.replaceFirst("\\?", java.util.regex.Matcher.quoteReplacement("$" + i));
        }
        String execute = "EXECUTE follower_plan(" + String.join(",", values) + ")";
        var plans = new java.util.ArrayList<String>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("PREPARE follower_plan AS " + sql);
                try {
                    for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
                        statement.execute("SET LOCAL plan_cache_mode = " + mode);
                        try (var rows = statement.executeQuery("EXPLAIN " + execute)) {
                            System.out.println("Bounded follower estimated " + mode + ":");
                            while (rows.next()) System.out.println(rows.getString(1));
                        }
                        var savepoint = connection.setSavepoint();
                        var plan = new StringBuilder(mode).append('\n');
                        try (var rows = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + execute)) {
                            while (rows.next()) plan.append(rows.getString(1)).append('\n');
                        } finally { connection.rollback(savepoint); }
                        plans.add(plan.toString());
                    }
                } finally { statement.execute("DEALLOCATE follower_plan"); }
            } finally { connection.rollback(); }
        }
        return plans;
    }

    @Test void concurrentFollowerDelayCannotBeSkippedByTheSetClaim() throws Exception {
        for (int i = 0; i < 3; i++) insert(0, "delayed-prefix");
        var candidates = repository.findDueOrderedBatch(BASE);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
             var connection = java.sql.DriverManager.getConnection(jdbcUrl(), "socp", "socp-test")) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("update t_ingestion_outbox set next_attempt_at=? where id=?")) {
                statement.setTimestamp(1, java.sql.Timestamp.from(BASE.plusSeconds(60)));
                statement.setString(2, candidates.get(1).getId());
                assertEquals(1, statement.executeUpdate());
            }
            var claim = executor.submit(() -> {
                try (var ignored = TenantContext.open("tenant-a")) {
                    return batches.claimOrderedPrefixes(candidates, "delayed-owner", 12, BASE);
                }
            });
            boolean waiting = false;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from pg_stat_activity "
                        + "where datname='outbox' and wait_event_type='Lock' and query like ?)", Boolean.class,
                        "select id, routing_key, sequence_no, status, attempts, next_attempt_at%"));
                if (!waiting) Thread.sleep(10);
            }
            assertTrue(waiting, "candidate eligibility must wait for the concurrent row update");
            assertFalse(claim.isDone());
            connection.commit();
            assertEquals(List.of(candidates.getFirst().getId()), claim.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    .stream().map(row -> row.getId()).toList());
            assertEquals(2, repository.countByStatus("PENDING"));
        }
    }

    @Test void partialSetUpdateReceiptRollsBackTheLeaseAndEveryAttempt() {
        for (int i = 0; i < 3; i++) insert(0, "partial-prefix");
        var candidates = repository.findDueOrderedBatch(BASE);
        jdbc.execute("create function skip_prefix_claim() returns trigger language plpgsql as $$ begin "
                + "if NEW.id = '" + candidates.get(1).getId() + "' then return null; end if; return NEW; end $$");
        try {
            jdbc.execute("create trigger skip_prefix_claim before update on t_ingestion_outbox for each row "
                    + "when (NEW.status='PROCESSING' and NEW.processing_key is null) execute function skip_prefix_claim()");
            try {
                assertThrows(RuntimeException.class,
                        () -> batches.claimOrderedPrefixes(candidates, "partial-owner", 12, BASE));
                assertEquals(3, repository.countByStatus("PENDING"));
                assertEquals(0, jdbc.queryForObject("select sum(attempts) from t_ingestion_outbox", Integer.class));
                assertEquals(0, jdbc.queryForObject("select count(*) from t_ingestion_outbox "
                        + "where claim_token is not null or processing_key is not null", Integer.class));
            } finally { jdbc.execute("drop trigger skip_prefix_claim on t_ingestion_outbox"); }
        } finally { jdbc.execute("drop function skip_prefix_claim()"); }
    }

    private void vacuumFixture(boolean analyze) throws java.sql.SQLException {
        // Heap maintenance is fixture preparation, not the measured selector.
        // A separate connection keeps its budget out of the repository pool.
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl(), "socp", "socp-test");
             var statement = connection.createStatement()) {
            statement.execute("SET statement_timeout = '60s'");
            statement.execute(analyze ? "VACUUM (ANALYZE) t_ingestion_outbox" : "VACUUM t_ingestion_outbox");
        }
        assertEquals("5s", jdbc.queryForObject("SHOW statement_timeout", String.class),
                "fixture maintenance must preserve the repository statement timeout");
    }
}
