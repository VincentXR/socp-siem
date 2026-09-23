package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DetectionRoutingTopologyPostgresConcurrencyTest extends DetectionRoutingTopologyGuardTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(MiddlewareImages.postgres())
            .withDatabaseName("routing").withUsername("socp").withPassword("socp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void firstPinWaitsForConcurrentRuleMutationAndReadsItsCommittedTopology() throws Exception {
        var guard = new DetectionRoutingTopologyGuard(topology, rules, transactions, 8);
        var store = new RuleSpecStore(rules, revisions, conflicts, catalog, guard);
        String tenant = "audit-topology-race";
        TenantContext.set(tenant);
        store.save(rule("RACE", "custom.entity", "ACTIVE"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch pinStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var mutation = executor.submit(() -> TenantContext.runWith(tenant, () -> guard.mutate(tenant, () -> {
                locked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return store.save(rule("RACE", "changed.entity", "ACTIVE"));
            })));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var pin = executor.submit(() -> TenantContext.runWith(tenant, () -> {
                    pinStarted.countDown();
                    guard.pinIfNeeded(tenant, () -> DetectionRoutingPlan.compile(store.list(), 8));
                }));
                assertTrue(pinStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pin.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                mutation.get(5, TimeUnit.SECONDS);
                pin.get(5, TimeUnit.SECONDS);
                assertTrue(guard.compatible(tenant, DetectionRoutingPlan.compile(store.list(), 8)));
                assertEquals("changed.entity", store.get("RACE").get("groupBy"));
            } finally { release.countDown(); }
        }
    }
}
