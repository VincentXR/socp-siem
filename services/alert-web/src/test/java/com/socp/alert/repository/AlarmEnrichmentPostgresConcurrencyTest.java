package com.socp.alert.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL proof that a stale writer cannot erase an asynchronous enrichment patch. */
@DataJpaTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AlarmEnrichmentPostgresConcurrencyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("alert")
            .withUsername("socp_test")
            .withPassword("socp_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private AlarmRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void staleEntityCommitPreservesEnrichmentAndBothLogicalUpdates() throws Exception {
        String alarmId = inTransaction(() -> {
            TenantContext.set("tenant-a");
            Alarm alarm = new Alarm("R-1", "IOC", Severity.HIGH,
                    "connection to 203.0.113.10", "203.0.113.10");
            alarm.setTenantId("tenant-a");
            alarm.setSourceAlertId("source-1");
            return repository.saveAndFlush(alarm).getId();
        });

        CountDownLatch staleEntityLoaded = new CountDownLatch(1);
        CountDownLatch enrichmentCommitted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stateUpdate = executor.submit(() -> inTransaction(() -> {
                TenantContext.set("tenant-a");
                Alarm stale = repository.findByTenantIdAndId("tenant-a", alarmId).orElseThrow();
                staleEntityLoaded.countDown();
                await(enrichmentCommitted);
                stale.setStatus("CLOSED");
                repository.flush();
                return null;
            }));
            var enrichment = executor.submit(() -> {
                assertThat(staleEntityLoaded.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return inTransaction(() -> {
                        TenantContext.set("tenant-a");
                        return repository.updateEnrichment("tenant-a", alarmId,
                                "[{\"ioc\":\"203.0.113.10\"}]", 87, "CRITICAL");
                    });
                } finally {
                    enrichmentCommitted.countDown();
                }
            });

            assertThat(enrichment.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            stateUpdate.get(10, TimeUnit.SECONDS);
        }

        Alarm reloaded = inTransaction(() -> {
            TenantContext.set("tenant-a");
            return repository.findByTenantIdAndId("tenant-a", alarmId).orElseThrow();
        });
        assertThat(reloaded.getStatus()).isEqualTo("CLOSED");
        assertThat(reloaded.getTiHits()).contains("203.0.113.10");
        assertThat(reloaded.getRiskScore()).isEqualTo(87);
        assertThat(reloaded.getRiskLevel()).isEqualTo("CRITICAL");
        long tenantAlarmCount = inTransaction(() -> {
            TenantContext.set("tenant-a");
            return repository.findByTenantId("tenant-a").size();
        });
        assertThat(tenantAlarmCount).isEqualTo(1);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for concurrent enrichment");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for concurrent enrichment", interrupted);
        }
    }
}
