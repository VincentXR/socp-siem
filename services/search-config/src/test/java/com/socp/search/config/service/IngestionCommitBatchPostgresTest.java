package com.socp.search.config.service;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.jpa.repository.Query;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class IngestionCommitBatchPostgresTest extends IngestionCommitBatchPersistenceTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.postgres()))
            .withEnv("POSTGRES_DB", "ingest_batch").withEnv("POSTGRES_USER", "batch_test")
            .withEnv("POSTGRES_PASSWORD", "batch-test-only").withExposedPorts(5432)
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
