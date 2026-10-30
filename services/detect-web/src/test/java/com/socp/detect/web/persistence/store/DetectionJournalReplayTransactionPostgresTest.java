package com.socp.detect.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The bounded persistence-context proof also runs against the actual PostgreSQL driver. */
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class DetectionJournalReplayTransactionPostgresTest extends DetectionJournalReplayTransactionTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("detect_replay")
            .withUsername("replay_test")
            .withPassword("replay-test-only")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
    }

    @org.junit.jupiter.api.Test
    void deliveryLookupKeepsBothIndexBoundsForANewTenantWithStaleStatistics() throws Exception {
        String newTenant = "lookup-new-" + java.util.UUID.randomUUID();
        jdbc.execute("ALTER TABLE t_detection_event SET (autovacuum_enabled = false)");
        try {
            jdbc.update("""
                    insert into t_detection_event
                        (event_id, tenant_id, source_event_id, delivery_id, routing_version,
                         source, host, raw_event, fields_json, severity, occurred_at, status)
                    select 'lookup-history-' || n, 'lookup-retained-' || (n % 20),
                           'lookup-history-' || n, 'lookup-history-' || n, 'legacy-v1',
                           'test', 'host', repeat('x', 450), '{}', 'INFO', now(), 'COMPLETED'
                    from generate_series(1, 12000) as n
                    """);
            jdbc.execute("ANALYZE t_detection_event");
            jdbc.update("""
                    insert into t_detection_event
                        (event_id, tenant_id, source_event_id, delivery_id, routing_version,
                         source, host, raw_event, fields_json, severity, occurred_at, status)
                    select 'lookup-new-' || n, ?, 'lookup-' || n, 'lookup-' || n, 'legacy-v1',
                           'test', 'host', repeat('x', 450), '{}', 'INFO', now(), 'PENDING'
                    from generate_series(1, 4000) as n
                    """, newTenant);
            var ids = java.util.stream.IntStream.rangeClosed(1, 32).mapToObj(n -> "lookup-" + n).toList();
            String sql = com.socp.detect.web.persistence.repository.DetectionEventRepository.class
                    .getMethod("findByTenantIdAndDeliveryIdIn", String.class, java.util.Collection.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                    .replace(":tenantId", "'" + newTenant + "'")
                    .replace(":deliveryIds", "'" + String.join("','", ids) + "'");
            String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
            // Composite scans and bitmap intersections may both apply the two
            // bounds before fetching payloads. An ID filter after fetching a
            // tenant's entire journal would reintroduce the observed bad plan.
            String indexConditions = plan.lines().filter(line -> line.contains("Index Cond:"))
                    .collect(java.util.stream.Collectors.joining("\n"));
            org.junit.jupiter.api.Assertions.assertTrue(indexConditions.contains("tenant_id")
                    && indexConditions.contains("delivery_id"), plan);
            org.junit.jupiter.api.Assertions.assertFalse(plan.lines().anyMatch(line -> line.contains("Filter:")
                    && line.contains("delivery_id")), plan);
            try (var ignored = com.socp.platform.tenant.context.TenantContext.open(newTenant)) {
                var rows = repository.findByTenantIdAndDeliveryIdIn(newTenant, ids);
                org.junit.jupiter.api.Assertions.assertEquals(new java.util.HashSet<>(ids), rows.stream()
                        .map(com.socp.detect.web.persistence.entity.DetectionEventEntity::getDeliveryId)
                        .collect(java.util.stream.Collectors.toSet()));
                org.junit.jupiter.api.Assertions.assertEquals(ids.size(), rows.size());
                org.junit.jupiter.api.Assertions.assertTrue(rows.stream().allMatch(row -> newTenant.equals(row.getTenantId())));
            }
        } finally {
            jdbc.update("delete from t_detection_event where tenant_id=? or tenant_id like 'lookup-retained-%'", newTenant);
            jdbc.execute("ALTER TABLE t_detection_event RESET (autovacuum_enabled)");
        }
    }

    @org.junit.jupiter.api.Test
    void batchFenceBlocksTakeoverUntilTheOuterDurableTransactionEnds() throws Exception {
        String topic = "fence-race-" + java.util.UUID.randomUUID();
        var leases = java.util.stream.IntStream.range(0, 3)
                .mapToObj(partition -> ownership.acquire(topic, partition, 0)).toList();
        var first = leases.getFirst();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        var submitted = new java.util.concurrent.CountDownLatch(1);
        var contenderPid = new java.util.concurrent.atomic.AtomicInteger();
        var takeover = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                ownership.assertCurrentBatch(leases);
                takeover.set(executor.submit(() -> transaction.execute(inner -> {
                    contenderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    submitted.countDown();
                    // Simulated future expiry allows takeover after the holder
                    // releases its locks, even though this call starts earlier.
                    java.time.Instant future = java.time.Instant.now().plusSeconds(60);
                    return owners.claimExisting(first.unitKey(), "successor", future, future.plusSeconds(30));
                })));
                try {
                    org.junit.jupiter.api.Assertions.assertTrue(submitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    boolean blocked = false;
                    while (System.nanoTime() < deadline) {
                        jdbc.execute("select pg_stat_clear_snapshot()");
                        blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                                "select wait_event_type='Lock' from pg_stat_activity where pid=?", Boolean.class, contenderPid.get()));
                        if (blocked) break;
                        Thread.sleep(10);
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(blocked, "PostgreSQL must report the contender waiting for the held fence");
                    org.junit.jupiter.api.Assertions.assertFalse(takeover.get().isDone());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            org.junit.jupiter.api.Assertions.assertEquals(1, takeover.get().get(10, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                    () -> transaction.executeWithoutResult(status -> ownership.assertCurrentBatch(leases)));
        } finally {
            jdbc.update("delete from t_detection_state_owner where input_topic=?", topic);
        }
    }
}
