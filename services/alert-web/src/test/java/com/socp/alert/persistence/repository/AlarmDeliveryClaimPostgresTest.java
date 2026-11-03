package com.socp.alert.persistence.repository;

import com.socp.platform.test.MiddlewareImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class AlarmDeliveryClaimPostgresTest extends AlarmDeliveryClaimPersistenceTest {
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
        registry.add("spring.datasource.url", AlarmDeliveryClaimPostgresTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET statement_timeout = '5s'");
    }

    @Test void duePayloadReadsAndMaintenanceDoNotScanRetainedRetryHistory() throws Exception {
        jdbc.execute("alter table alarm_delivery set (autovacuum_enabled=false)");
        try {
            jdbc.update("""
                    insert into alarm_delivery
                        (id,tenant_id,alarm_id,destination,payload,status,attempts,next_attempt_at,created_at,updated_at)
                    select 'history-'||n,'tenant-a','history-'||n,'NOTIFY',repeat('x',1500),
                           case when n<=30000 then 'DEAD' else 'PENDING' end,0,
                           timestamp '2026-09-02',timestamp '2026-08-01',timestamp '2026-08-01'
                    from generate_series(1,50000) n
                    """);
            jdbc.update("""
                    insert into alarm_delivery
                        (id,tenant_id,alarm_id,destination,payload,status,attempts,next_attempt_at,created_at,updated_at)
                    select 'due-'||lpad(n::text,4,'0'),'tenant-a','due-'||n,'NOTIFY','retained-payload',
                           'PENDING',0,timestamp '2026-09-01',timestamp '2026-08-02',timestamp '2026-08-02'
                    from generate_series(1,120) n
                    """);
            jdbc.execute("vacuum (analyze) alarm_delivery");
            var selected = repository.findDueBatch("PENDING", BASE);
            assertEquals(100, selected.size());
            assertEquals("due-0001", selected.getFirst().getId());
            assertEquals("due-0100", selected.getLast().getId());
            assertTrue(selected.stream().allMatch(row -> "retained-payload".equals(row.getPayload())));
            assertEquals(20120, repository.countByStatus("PENDING"));
            assertEquals(30000, repository.countByStatus("DEAD"));
            String selection = AlarmDeliveryRepository.class.getMethod(
                    "findDueBatch", String.class, java.time.Instant.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                    .replace(":status", "'PENDING'").replace(":nextAttemptAt", "timestamp '2026-09-01'");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var plan = mapper.readTree(jdbc.queryForObject("explain (analyze,buffers,format json) " + selection, String.class))
                    .get(0).get("Plan");
            int buffers = plan.path("Shared Hit Blocks").asInt() + plan.path("Shared Read Blocks").asInt();
            assertTrue(buffers < 1000, "100 due deliveries must not read 50,000 historical payloads: " + plan);
            String maintenance = AlarmDeliveryRepository.class.getMethod("markExhaustedBatch",
                    int.class, String.class, java.time.Instant.class, int.class)
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                    .replace(":maxAttempts", "12").replace(":reason", "'exhausted'")
                    .replace(":now", "timestamp '2026-09-01'").replace(":batchSize", "100");
            plan = mapper.readTree(jdbc.queryForObject("explain (analyze,buffers,format json) " + maintenance, String.class))
                    .get(0).get("Plan");
            buffers = plan.path("Shared Hit Blocks").asInt() + plan.path("Shared Read Blocks").asInt();
            assertTrue(buffers < 1000, "empty exhaustion pass must probe the attempt bound: " + plan);
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    statement.execute("set local plan_cache_mode='force_generic_plan'");
                    String genericMaintenance = AlarmDeliveryRepository.class.getMethod("markExhaustedBatch",
                            int.class, String.class, java.time.Instant.class, int.class)
                            .getAnnotation(org.springframework.data.jpa.repository.Query.class).value()
                            .replace(":maxAttempts", "$1").replace(":reason", "$2")
                            .replace(":now", "$3").replace(":batchSize", "$4");
                    statement.execute("prepare maintenance_probe(integer,varchar,timestamptz,integer) as "
                            + genericMaintenance);
                    try (var rows = statement.executeQuery("explain(analyze,buffers,format json) "
                            + "execute maintenance_probe(12,'exhausted',timestamp '2026-09-01',100)")) {
                        assertTrue(rows.next());
                        var generic = mapper.readTree(rows.getString(1)).get(0).get("Plan");
                        int work = generic.path("Shared Hit Blocks").asInt() + generic.path("Shared Read Blocks").asInt();
                        assertTrue(work < 32, "generic maintenance must seek exhausted attempts, not scan pending history: " + generic);
                    }
                    statement.execute("deallocate maintenance_probe");
                    for (String column : java.util.List.of("created_at", "updated_at")) {
                        String status = column.equals("created_at") ? "PENDING" : "DEAD";
                        statement.execute("prepare age_probe(varchar) as select min(" + column
                                + ") from alarm_delivery where status=$1");
                        try (var rows = statement.executeQuery("explain(analyze,buffers,format json) execute age_probe('"
                                + status + "')")) {
                            assertTrue(rows.next());
                            var agePlan = mapper.readTree(rows.getString(1)).get(0).get("Plan");
                            int ageBuffers = agePlan.path("Shared Hit Blocks").asInt() + agePlan.path("Shared Read Blocks").asInt();
                            assertTrue(ageBuffers < 32, "an age gauge must seek the first status entry: " + agePlan);
                        }
                        statement.execute("deallocate age_probe");
                    }
                } finally {
                    connection.rollback();
                }
            }
            assertEquals(50120, jdbc.queryForObject("select count(*) from alarm_delivery", Integer.class));
        } finally {
            jdbc.execute("alter table alarm_delivery reset (autovacuum_enabled)");
        }
    }

    @Test void migrationPreservesExistingClaims() {
        Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                .schemas("claim_upgrade").locations("classpath:db/migration").target("20").load().migrate();
        jdbc.update("insert into claim_upgrade.alarm_delivery "
                + "(id, tenant_id, alarm_id, destination, payload, status, attempts, next_attempt_at, created_at, updated_at) "
                + "values ('upgrade-id', 'tenant-a', 'upgrade-alarm', 'NOTIFY', '{}', 'PROCESSING', 3, now(), now(), now())");

        Flyway.configure().dataSource(jdbcUrl(), "socp", "socp-test")
                .schemas("claim_upgrade").locations("classpath:db/migration").load().migrate();

        String options = jdbc.queryForObject("select reloptions::text from pg_class "
                + "where oid='claim_upgrade.alarm_delivery'::regclass", String.class);
        assertTrue(options.contains("autovacuum_vacuum_scale_factor=0"));
        assertTrue(options.contains("autovacuum_vacuum_threshold=10000"));
        assertTrue(options.contains("vacuum_index_cleanup=on"));
        var stored = jdbc.queryForMap("select * from claim_upgrade.alarm_delivery where id = 'upgrade-id'");
        assertEquals("PROCESSING", stored.get("status"));
        assertEquals(3, stored.get("attempts"));
        assertEquals("tenant-a", stored.get("tenant_id"));
        assertNull(stored.get("claim_token"));
        assertNull(stored.get("traceparent"), "legacy deliveries must remain valid without invented parents");
        String parent = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        jdbc.update("update claim_upgrade.alarm_delivery set traceparent = ? where id = 'upgrade-id'", parent);
        assertEquals(parent, jdbc.queryForObject(
                "select traceparent from claim_upgrade.alarm_delivery where id = 'upgrade-id'", String.class));
    }
}
