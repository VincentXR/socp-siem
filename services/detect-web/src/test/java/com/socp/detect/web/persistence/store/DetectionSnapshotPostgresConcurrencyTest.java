package com.socp.detect.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.state.DetectionStateSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaDetectionStateSnapshotStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DetectionSnapshotPostgresConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("detect").withUsername("socp").withPassword("socp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired private JpaDetectionStateSnapshotStore store;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void tenant() { TenantContext.set("snapshot-test"); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void staleTransactionCannotCommitAnyPartOfGeneration() throws Exception {
        Instant time = Instant.parse("2026-09-10T12:00:00Z");
        store.saveAll(generation("race", time, 10));
        var loaded = new CountDownLatch(1);
        var newerCommitted = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var stale = executor.submit(() -> {
                TenantContext.set("snapshot-test");
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        store.latest("snapshot-test", "race-a", 0).orElseThrow();
                        store.latest("snapshot-test", "race-b", 0).orElseThrow();
                        loaded.countDown();
                        await(newerCommitted);
                        store.saveAll(generation("race", time.plusSeconds(1), 11));
                    });
                } finally { TenantContext.clear(); }
            });
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
                store.saveAll(generation("race", time.plusSeconds(2), 20));
            } finally { newerCommitted.countDown(); }
            assertThatThrownBy(() -> stale.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        }
        for (String rule : List.of("race-a", "race-b")) {
            assertThat(store.latest("snapshot-test", rule, 0).orElseThrow().partitionOffsets()).containsEntry(0, 20L);
        }
    }

    @Test
    void rejectedBatchDoesNotLeakHibernateDirtyUpdates() {
        Instant time = Instant.parse("2026-09-10T13:00:00Z");
        store.save(snapshot("dirty-a", time, 8));
        store.save(snapshot("dirty-b", time, 12));
        store.saveAll(generation("dirty", time.plusSeconds(1), 10));
        assertThat(store.latest("snapshot-test", "dirty-a", 0).orElseThrow().partitionOffsets()).containsEntry(0, 8L);
        assertThat(store.latest("snapshot-test", "dirty-b", 0).orElseThrow().partitionOffsets()).containsEntry(0, 12L);
    }

    private static List<DetectionStateSnapshot> generation(String prefix, Instant time, long offset) {
        return List.of(snapshot(prefix + "-a", time, offset), snapshot(prefix + "-b", time, offset));
    }

    private static DetectionStateSnapshot snapshot(String rule, Instant time, long offset) {
        return new DetectionStateSnapshot(rule, "v1", "snapshot-test", 0, offset,
                new byte[]{1}, time, Map.of(0, offset));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("checkpoint coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
