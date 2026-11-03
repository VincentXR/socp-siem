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
