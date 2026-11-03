package com.socp.detect.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic result batches against the production database driver and migrations. */
@Testcontainers
@ActiveProfiles("pg")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class DetectionBatchPostgresTest extends DetectionBatchPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("detect_batch")
            .withUsername("replay_test")
            .withPassword("replay-test-only")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Test
    void pendingStatisticsUseActiveIndexWhenStatisticsContainOnlyTerminalHistory() throws Exception {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        String history = tenant + "-history";
        try {
            seedHistory(history, 100_000);
            jdbc.update("update t_detection_event set status='COMPLETED',completed_at=now() where tenant_id=?", history);
            jdbc.execute("analyze t_detection_event");
            seedHistory(tenant, 32);
            jdbc.update("update t_detection_event set delivery_topic='socp-events' where tenant_id=?", tenant);
            assertEquals(32, repository.countByTenantIdAndPendingTopic(tenant, "socp-events"));
            String sql = com.socp.detect.web.persistence.repository.DetectionEventRepository.class
                    .getMethod("countByTenantIdAndPendingTopic", String.class, String.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                    .replace(":tenant", "?").replace(":topic", "?");
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("explain(analyze,buffers,format json) " + sql)) {
                    statement.setString(1, tenant);
                    statement.setString(2, "socp-events");
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        try {
                            var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1))
                                    .get(0).get("Plan");
                            int blocks = plan.path("Shared Hit Blocks").asInt() + plan.path("Shared Read Blocks").asInt();
                            assertTrue(plan.toString().contains("idx_detection_event_pending_count"), plan.toString());
                            assertTrue(blocks < 128, "pending count scanned retained history: " + plan);
                        } catch (java.io.IOException failure) { throw new java.sql.SQLException(failure); }
                    }
                }
                return null;
            });
        } finally {
            jdbc.update("delete from t_detection_event where tenant_id=?", history);
        }
    }

    @Test
    void partitionTailUsesDeliveryPositionsWithoutScanningOtherTenantHistory() throws Exception {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        String history = tenant + "-history";
        try {
            jdbc.update("""
                    insert into t_detection_event(event_id,tenant_id,source_event_id,delivery_id,routing_version,
                        source,host,raw_event,fields_json,severity,occurred_at,status,completed_at,result_json,
                        delivery_topic,delivery_partition,delivery_offset)
                    select ?||'-'||n,case when n%100=0 then ? else ? end,'tail-'||n,'tail-'||n,'legacy-v1',
                        'auth','host',repeat('x',800),'{}','INFO',now(),'COMPLETED',now(),'{}',
                        'socp-events',0,n from generate_series(1,100000) n
                    """, history, tenant, history);
            jdbc.execute("analyze t_detection_event");
            var tail = repository.partitionTail(tenant, "COMPLETED", "socp-events", 0, 0,
                    org.springframework.data.domain.PageRequest.of(0, 32));
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 32).map(n -> n * 100).boxed().toList(),
                    tail.stream().map(com.socp.detect.web.persistence.entity.DetectionEventEntity::getKafkaOffset).toList());
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("""
                        explain(analyze,buffers,format json) select * from t_detection_event
                        where tenant_id=? and status='COMPLETED' and delivery_topic='socp-events'
                          and delivery_partition=0 and delivery_offset>0
                        order by delivery_offset,completed_at,delivery_id limit 32
                        """)) {
                    statement.setString(1, tenant);
                    try (var rows = statement.executeQuery()) {
                        rows.next();
                        try {
                            var plan = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1))
                                    .get(0).get("Plan");
                            int blocks = plan.path("Shared Hit Blocks").asInt() + plan.path("Shared Read Blocks").asInt();
                            assertTrue(blocks < 128, "32 replay rows scanned retained history instead of delivery positions: " + plan);
                        } catch (java.io.IOException failure) { throw new java.sql.SQLException(failure); }
                    }
                }
                return null;
            });
            assertEquals(1000L, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
        } finally {
            jdbc.update("delete from t_detection_event where tenant_id=?", history);
        }
    }

    @Test
    void completionUsesOneDatabaseUpdateWithoutScanningFreshTenantHistory() {
        jdbc.execute("create extension if not exists pg_stat_statements");
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        String historyTenant = tenant + "-history";
        jdbc.execute("alter table t_detection_event set (autovacuum_enabled=false)");
        try {
            seedHistory(historyTenant, 100_000);
            jdbc.execute("analyze t_detection_event");
            // Model a tenant whose retained rows arrived after the last ANALYZE.
            seedHistory(tenant, 20_000);
            assertCompletionStatements(tenant);
        } finally {
            jdbc.update("delete from t_detection_event where tenant_id=?", historyTenant);
            jdbc.execute("alter table t_detection_event reset (autovacuum_enabled)");
        }
    }

    private void seedHistory(String tenant, int count) {
        jdbc.update("insert into t_detection_event(event_id,tenant_id,source_event_id,delivery_id,routing_version,"
                + "source,host,fields_json,severity,occurred_at,status,result_json) "
                + "select ?||'-'||n,?,'retained-'||n,'retained-'||n,'legacy-v1',"
                + "'auth','host','{}','INFO',now(),'PENDING','{}' from generate_series(1,?) n", tenant, tenant, count);
    }

    private void assertCompletionStatements(String tenant) {
        var inputs = java.util.stream.IntStream.range(0, 32).mapToObj(n -> new DetectionEventInput(
                new com.socp.rule.model.SecurityEvent("set-update-" + n, java.time.Instant.now(),
                        "auth", "host", "raw", java.util.Map.of("tenant_id", tenant),
                        com.socp.rule.model.Severity.INFO), "events", 0, n, "route")).toList();
        jdbc.execute("select pg_stat_statements_reset()");
        journal.claimBatch(inputs);
        assertEquals(1L, jdbc.queryForObject("select sum(calls)::bigint from pg_stat_statements "
                + "where query like 'insert into t_detection_event%'", Long.class),
                "The PostgreSQL profile must combine JDBC insert batches into multi-row statements");
        jdbc.execute("select pg_stat_statements_reset()");
        var results = inputs.stream().map(input -> new com.socp.rule.engine.DetectionResult(input.event(),
                new com.socp.rule.engine.DetectionResult.InputPosition(input.topic(), input.partition(), input.offset()),
                java.util.Map.of("rule", "v1"), java.util.List.of(), java.util.List.of(), java.util.List.of(),
                null, input.event().scopedId())).toList();
        forwarder.forwardBatch(results, () -> { });
        assertEquals(32, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
        assertEquals(1L, jdbc.queryForObject("select sum(calls)::bigint from pg_stat_statements "
                + "where (query like 'update t_detection_event%' or query like 'merge into t_detection_event%')", Long.class),
                "JDBC executeBatch still executes one server UPDATE per event; completion must be set based");
        long buffers = jdbc.queryForObject("select sum(shared_blks_hit+shared_blks_read)::bigint from pg_stat_statements "
                + "where query like 'merge into t_detection_event%'", Long.class);
        assertTrue(buffers < 2_000, "32 completions scanned retained tenant history: buffers=" + buffers);
    }

    @Test
    void resultCompressionUpgradePreservesOldReceiptsAndUsesLz4ForNewWrites() {
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        var changes = java.util.stream.IntStream.range(0, 40).mapToObj(n ->
                new com.socp.rule.engine.DetectionResult.StateChange("rule-" + n,
                        com.socp.rule.engine.DetectionResult.digest(("before-" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        com.socp.rule.engine.DetectionResult.digest(("after-" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        true, "affected-keys-merkle-v1")).toList();
        var inputs = java.util.stream.IntStream.range(0, 2).mapToObj(n -> new DetectionEventInput(
                new com.socp.rule.model.SecurityEvent("compression-" + n, java.time.Instant.now(), "auth", "host", "raw",
                        java.util.Map.of("tenant_id", tenant), com.socp.rule.model.Severity.INFO),
                "events", 0, n, "route")).toList();
        journal.claimBatch(inputs);
        var results = inputs.stream().map(input -> new com.socp.rule.engine.DetectionResult(input.event(), null,
                java.util.Map.of("rule", "v1"), changes, java.util.List.of(), java.util.List.of(), null, null)).toList();
        jdbc.execute("alter table t_detection_event alter column result_json set compression pglz");
        forwarder.forwardBatch(results.subList(0, 1), () -> { });
        String stored = jdbc.queryForObject("select result_json from t_detection_event where tenant_id=? and delivery_id=?",
                String.class, tenant, "compression-0");
        assertEquals("pglz", jdbc.queryForObject("select pg_column_compression(result_json) from t_detection_event "
                + "where tenant_id=? and delivery_id=?", String.class, tenant, "compression-0"));
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var context = org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
            org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
            try { new db.migration.detect.journal.V34__journal_result_compression().migrate(context); }
            catch (Exception failure) { throw new java.sql.SQLException(failure); }
            return null;
        });
        assertEquals(stored, jdbc.queryForObject("select result_json from t_detection_event where tenant_id=? and delivery_id=?",
                String.class, tenant, "compression-0"));
        assertEquals("pglz", jdbc.queryForObject("select pg_column_compression(result_json) from t_detection_event "
                + "where tenant_id=? and delivery_id=?", String.class, tenant, "compression-0"));
        forwarder.forwardBatch(results.subList(1, 2), () -> { });
        assertEquals("lz4", jdbc.queryForObject("select pg_column_compression(result_json) from t_detection_event "
                + "where tenant_id=? and delivery_id=?", String.class, tenant, "compression-1"));
        assertEquals(2, repository.countByTenantIdAndStatus(tenant, "COMPLETED"));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
}
