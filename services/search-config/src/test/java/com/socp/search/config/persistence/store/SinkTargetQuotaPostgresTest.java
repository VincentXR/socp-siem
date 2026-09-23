package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.config.VectorProperties;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.persistence.repository.TenantCatalogEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false)
@Import(TenantCatalogPersistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class SinkTargetQuotaPostgresTest {
    @Container
    static final GenericContainer<?> DATABASE = new GenericContainer<>(MiddlewareImages.postgres())
            .withEnv("POSTGRES_DB", "sink_targets").withEnv("POSTGRES_USER", "socp")
            .withEnv("POSTGRES_PASSWORD", "socp-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + DATABASE.getHost()
                + ":" + DATABASE.getMappedPort(5432) + "/sink_targets");
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired TenantCatalogPersistence persistence;
    @Autowired TenantCatalogEntryRepository entries;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void twoReplicasCannotBothCreateTheFinalTarget() throws Exception {
        String tenant = UUID.randomUUID().toString();
        SinkTargetStore baseline = store(persistence);
        inTenant(tenant, () -> {
            for (int index = 0; index < 127; index++) baseline.save(target("target-" + index));
            return null;
        });

        CountDownLatch reads = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TenantCatalogPersistence gated = new TenantCatalogPersistence(entries, transactions) {
            private final AtomicInteger calls = new AtomicInteger();
            @Override List<StoredEntry> list(String type, String tenantId) {
                List<StoredEntry> result = super.list(type, tenantId);
                if ("sink_target".equals(type) && calls.incrementAndGet() <= 2) {
                    reads.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return result;
            }
        };
        SinkTargetStore first = store(gated);
        SinkTargetStore second = store(gated);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.save(target("last-a"))));
            var b = pool.submit(() -> outcome(tenant, () -> second.save(target("last-b"))));
            try { assertTrue(reads.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(Set.of(200, 400), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(128, inTenant(tenant, () -> baseline.list().size()));
        assertTrue(inTenant(tenant + "-other", () -> baseline.list().isEmpty()));
    }

    private SinkTargetStore store(TenantCatalogPersistence catalogPersistence) {
        return new SinkTargetStore(catalogPersistence, mapper, new VectorProperties());
    }

    private static SinkTarget target(String name) {
        return SinkTarget.create(name, "HTTP", "https://example.test/ingest", null, true);
    }

    private static int outcome(String tenant, Supplier<?> operation) {
        try { inTenant(tenant, operation); return 200; }
        catch (ApiException failure) { return failure.getCode(); }
    }

    private static <T> T inTenant(String tenant, Supplier<T> operation) {
        TenantContext.set(tenant);
        try { return operation.get(); }
        finally { TenantContext.clear(); }
    }
}
