package com.socp.detect.web.persistence.store;

import com.socp.platform.test.MiddlewareImages;
import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DetectionSnapshotPostgresConcurrencyTest extends DetectionSnapshotPersistenceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("detect").withUsername("socp").withPassword("socp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Test
    void checkpointCompressionUpgradeKeepsOldBytesAndPositionVectorsReadable() {
        byte[] state = java.util.stream.IntStream.range(0, 4000)
                .mapToObj(n -> "state-" + n + "-" + "evidence".repeat(20))
                .collect(java.util.stream.Collectors.joining("\n"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant time = Instant.parse("2026-09-10T14:00:00Z");
        jdbc.execute("alter table t_detection_state_snapshot alter column serialized_state set compression pglz");
        store.save(new DetectionStateSnapshot("compression-old", "v1", "snapshot-test", 0, 10,
                state, time, Map.of(0, 10L), Map.of(), "socp-events"));
        assertThat(jdbc.queryForObject("select pg_column_compression(serialized_state) from t_detection_state_snapshot "
                + "where tenant_id='snapshot-test' and rule_id='compression-old'", String.class)).isEqualTo("pglz");
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            var context = org.mockito.Mockito.mock(org.flywaydb.core.api.migration.Context.class);
            org.mockito.Mockito.when(context.getConnection()).thenReturn(connection);
            try { new db.migration.detect.journal.V37__snapshot_state_compression().migrate(context); }
            catch (Exception failure) { throw new java.sql.SQLException(failure); }
            return null;
        });
        assertThat(store.latest("snapshot-test", "compression-old", 0).orElseThrow().serializedState()).containsExactly(state);
        assertThat(jdbc.queryForObject("select pg_column_compression(serialized_state) from t_detection_state_snapshot "
                + "where tenant_id='snapshot-test' and rule_id='compression-old'", String.class)).isEqualTo("pglz");
        store.save(new DetectionStateSnapshot("compression-new", "v1", "snapshot-test", 0, 11,
                state, time.plusSeconds(1), Map.of(0, 11L), Map.of(), "socp-events"));
        assertThat(jdbc.queryForObject("select pg_column_compression(serialized_state) from t_detection_state_snapshot "
                + "where tenant_id='snapshot-test' and rule_id='compression-new'", String.class)).isEqualTo("lz4");
        var restored = store.latest("snapshot-test", "compression-new", 0).orElseThrow();
        assertThat(restored.serializedState()).containsExactly(state);
        assertThat(restored.partitionOffsets()).containsEntry(0, 11L);
    }

}
