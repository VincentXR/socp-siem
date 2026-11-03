package com.socp.search.config.service;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.jpa.repository.Query;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@ActiveProfiles("pg")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class IngestionCommitBatchPostgresTest extends IngestionCommitBatchPersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
            .withEnv("POSTGRES_DB", "ingest_batch").withEnv("POSTGRES_USER", "batch_test")
            .withEnv("POSTGRES_PASSWORD", "batch-test-only").withExposedPorts(5432)
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024)
                    .withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                            com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0),
                            new com.github.dockerjava.api.model.ExposedPort(5432))))
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/ingest_batch");
        registry.add("spring.datasource.username", () -> "batch_test");
        registry.add("spring.datasource.password", () -> "batch-test-only");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/ingest_batch");
        registry.add("spring.flyway.user", () -> "batch_test");
        registry.add("spring.flyway.password", () -> "batch-test-only");
    }

    @Test void separatePayloadIsCoveredByTheDeploymentTenantPolicy() throws Exception {
        var event = new com.socp.search.config.domain.SearchEvent("rls-payload", java.time.Instant.now(),
                "test", "host", "INFO", "private evidence", java.util.Map.of("tenant_id", tenant), java.util.Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(1, commits.commit(java.util.List.of(event)).created());
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("infra/postgres/tenant-rls.sql"))) root = root.getParent();
        org.junit.jupiter.api.Assertions.assertNotNull(root);
        jdbc.execute(java.nio.file.Files.readString(root.resolve("infra/postgres/tenant-rls.sql")));
        jdbc.execute("create role payload_reader nosuperuser nobypassrls");
        jdbc.execute("grant select on t_ingestion_outbox_payload to payload_reader");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("set role payload_reader");
                try {
                    for (String scope : java.util.List.of(tenant, "other-tenant", "", "*")) {
                        try (var setting = connection.prepareStatement("select set_config('socp.tenant_id',?,false)")) {
                            setting.setString(1, scope);
                            setting.execute();
                        }
                        try (var count = connection.prepareStatement(
                                "select count(*) from t_ingestion_outbox_payload where tenant_id=?")) {
                            count.setString(1, tenant);
                            try (var rows = count.executeQuery()) {
                                rows.next();
                                org.junit.jupiter.api.Assertions.assertEquals(
                                        scope.equals(tenant) || scope.equals("*") ? 1 : 0, rows.getInt(1));
                            }
                        }
                    }
                } finally {
                    statement.execute("reset role");
                    statement.execute("reset socp.tenant_id");
                }
            }
            return null;
        });
    }

    @Test void postgresBatchesInsertOnTheServerAndRetainSameKeyEnqueueOrder() {
        jdbc.execute("create extension if not exists pg_stat_statements");
        jdbc.execute("select pg_stat_statements_reset()");
        var events = java.util.stream.IntStream.range(0, 128).mapToObj(index ->
                new com.socp.search.config.domain.SearchEvent("ordered-batch-" + index,
                        java.time.Instant.parse("2026-10-29T00:00:00Z"), "test", "shared-host", "INFO",
                        "event " + index, java.util.Map.of("tenant_id", tenant), java.util.Map.of())).toList();
        org.junit.jupiter.api.Assertions.assertEquals(128, commits.commit(events).created());
        for (String table : java.util.List.of("t_search_event", "t_ingestion_outbox", "t_ingestion_outbox_payload")) {
            org.junit.jupiter.api.Assertions.assertEquals(4L, jdbc.queryForObject(
                    "select sum(calls)::bigint from pg_stat_statements where query like ?", Long.class,
                    "insert into " + table + " (%"), "32-row JDBC batches must reach PostgreSQL as multi-row inserts");
        }
        org.junit.jupiter.api.Assertions.assertEquals(events.stream()
                        .map(com.socp.search.config.domain.SearchEvent::eventId).toList(),
                jdbc.queryForList("select event_id from t_ingestion_outbox where tenant_id=? order by sequence_no",
                        String.class, tenant), "Batch rewriting must retain the input order of a routing key");
        org.junit.jupiter.api.Assertions.assertEquals(128, commits.commit(events).duplicates());
        org.junit.jupiter.api.Assertions.assertEquals(128, outbox.findByTenantId(tenant).size());
    }

    @Test void identityLookupsKeepBothIndexBoundsForNewTenantsWithStaleStatistics() throws Exception {
        var tables = java.util.List.of("t_search_event", "t_ingestion_outbox");
        for (String table : tables) jdbc.execute("ALTER TABLE " + table + " SET (autovacuum_enabled = false)");
        try {
            jdbc.update("""
                    insert into t_search_event
                        (id, tenant_id, event_id, msg, fields_json, ecs_json, created_at, updated_at)
                    select 'identity-history-' || n, 'identity-retained-' || (n % 20),
                           'identity-history-' || n, repeat('x', 450), '{}', '{}', now(), now()
                    from generate_series(1, 12000) as n
                    """);
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'identity-history-' || n, 'identity-retained-' || (n % 20),
                           'identity-history-' || n, 'key-' || n, repeat('x', 450), 'PUBLISHED', 1,
                           now(), now(), now()
                    from generate_series(1, 12000) as n
                    """);
            for (String table : tables) jdbc.execute("ANALYZE " + table);
            jdbc.update("""
                    insert into t_search_event
                        (id, tenant_id, event_id, msg, fields_json, ecs_json, created_at, updated_at)
                    select 'identity-new-' || n, ?, 'identity-' || n, 'event ' || n, '{}', '{}', now(), now()
                    from generate_series(1, 4000) as n
                    """, tenant);
            jdbc.update("""
                    insert into t_ingestion_outbox
                        (id, tenant_id, event_id, routing_key, payload, status, attempts,
                         next_attempt_at, created_at, updated_at)
                    select 'identity-new-' || n, ?, 'identity-' || n, 'key-' || n, '{}', 'PENDING', 0,
                           now(), now(), now()
                    from generate_series(1, 4000) as n
                    """, tenant);
            var ids = java.util.stream.IntStream.rangeClosed(1, 100).mapToObj(n -> "identity-" + n).toList();
            for (Class<?> repository : java.util.List.of(SearchEventRepository.class, IngestionOutboxRepository.class)) {
                String sql = repository.getMethod("findByTenantIdAndEventIdIn", String.class, java.util.Collection.class)
                        .getAnnotation(Query.class).value().replace(":tenantId", "'" + tenant + "'")
                        .replace(":eventIds", "'" + String.join("','", ids) + "'");
                String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
                // A composite scan or a bitmap intersection can apply both
                // bounds before fetching payloads. Reject the stale-statistics
                // plan that fetches a whole tenant and only then filters IDs.
                String indexConditions = plan.lines().filter(line -> line.contains("Index Cond:"))
                        .collect(java.util.stream.Collectors.joining("\n"));
                org.junit.jupiter.api.Assertions.assertTrue(indexConditions.contains("tenant_id")
                        && indexConditions.contains("event_id"), plan);
                org.junit.jupiter.api.Assertions.assertFalse(plan.lines().anyMatch(line -> line.contains("Filter:")
                        && line.contains("event_id")), plan);
            }
            org.junit.jupiter.api.Assertions.assertEquals(100, canonical.findByTenantIdAndEventIdIn(tenant, ids).size());
            org.junit.jupiter.api.Assertions.assertEquals(100, outbox.findByTenantIdAndEventIdIn(tenant, ids).size());
        } finally {
            for (String table : tables) {
                jdbc.update("delete from " + table + " where tenant_id like 'identity-retained-%'");
                jdbc.execute("ALTER TABLE " + table + " RESET (autovacuum_enabled)");
            }
        }
    }
}
