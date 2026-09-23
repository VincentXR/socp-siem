package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.ReferenceSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import(TenantCatalogPersistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class ReferenceSetConcurrencyPostgresTest {
    @Container
    static final GenericContainer<?> DATABASE = new GenericContainer<>(MiddlewareImages.postgres())
            .withEnv("POSTGRES_DB", "catalog").withEnv("POSTGRES_USER", "socp").withEnv("POSTGRES_PASSWORD", "socp-test")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(5432) + "/catalog");
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired TenantCatalogPersistence persistence;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void simultaneousFirstTemplateEditsKeepBothAcknowledgedEntries() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var read = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var first = gated(read, release);
        var second = gated(read, release);
        String id = inTenant(tenant, () -> first.list().getFirst().id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> inTenant(tenant, () -> first.addEntry(id, "new-a")));
            var b = pool.submit(() -> inTenant(tenant, () -> second.addEntry(id, "new-b")));
            try { assertTrue(read.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertNotNull(a.get(15, TimeUnit.SECONDS));
            assertNotNull(b.get(15, TimeUnit.SECONDS));
        }
        assertTrue(inTenant(tenant, () -> first.get(id).entries()).containsAll(List.of("new-a", "new-b")));
        assertFalse(inTenant(tenant + "-other", () -> first.get(id).entries()).contains("new-a"));
    }

    @Test void simultaneousAddAndRemovePreserveBothChanges() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new ReferenceSetStore(persistence, mapper);
        var set = ReferenceSet.of("owned", "", List.of("remove", "keep"));
        inTenant(tenant, () -> initial.add(set));
        var read = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var first = gated(read, release);
        var second = gated(read, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> inTenant(tenant, () -> first.addEntry(set.id(), "add")));
            var b = pool.submit(() -> inTenant(tenant, () -> second.removeEntry(set.id(), "remove")));
            try { assertTrue(read.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertNotNull(a.get(15, TimeUnit.SECONDS));
            assertNotNull(b.get(15, TimeUnit.SECONDS));
        }
        assertEquals(List.of("keep", "add"), inTenant(tenant, () -> initial.get(set.id()).entries()));
    }

    @Test void deletionDoesNotAllowAnOlderEntryWriteToResurrectTheSet() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new ReferenceSetStore(persistence, mapper);
        var set = ReferenceSet.of("owned", "", List.of("keep"));
        inTenant(tenant, () -> initial.add(set));
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = gated(read, release);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var a = pool.submit(() -> inTenant(tenant, () -> first.addEntry(set.id(), "late")));
            try {
                assertTrue(read.await(10, TimeUnit.SECONDS));
                assertTrue(inTenant(tenant, () -> initial.delete(set.id())));
            } finally { release.countDown(); }
            assertNull(a.get(15, TimeUnit.SECONDS));
        }
        assertNull(inTenant(tenant, () -> initial.get(set.id())));
    }

    @Test void legacyRandomIdOverlayKeepsItsIdentityWithoutDuplicatingThePackagedRow() {
        String tenant = UUID.randomUUID().toString();
        var first = new ReferenceSetStore(persistence, mapper);
        var second = new ReferenceSetStore(persistence, mapper);
        ReferenceSet builtin = inTenant(tenant, () -> first.list().getFirst());
        ReferenceSet legacy = new ReferenceSet("REF-" + UUID.randomUUID().toString().substring(0, 8),
                builtin.name(), builtin.description(), List.of("legacy-entry"));
        inTenant(tenant, () -> first.add(legacy));
        assertEquals(1, inTenant(tenant, () -> second.list().stream()
                .filter(item -> builtin.name().equals(item.name())).count()));
        assertEquals(legacy, inTenant(tenant, () -> second.get(legacy.id())));
        assertEquals(legacy, inTenant(tenant, () -> second.list().stream()
                .filter(item -> builtin.name().equals(item.name())).findFirst().orElseThrow()));
        assertEquals(builtin.id(), inTenant(tenant + "-other", () -> second.list().stream()
                .filter(item -> builtin.name().equals(item.name())).findFirst().orElseThrow().id()));
    }

    @Test void concurrentAddsCannotExceedEntryCapacity() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new ReferenceSetStore(persistence, mapper);
        var set = ReferenceSet.of("full", "", java.util.stream.IntStream.range(0, 9_999)
                .mapToObj(index -> "entry-" + index).toList());
        inTenant(tenant, () -> initial.add(set));
        var read = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var first = gated(read, release);
        var second = gated(read, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.addEntry(set.id(), "new-a")));
            var b = pool.submit(() -> outcome(tenant, () -> second.addEntry(set.id(), "new-b")));
            try { assertTrue(read.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertEquals(java.util.Set.of(200, 400), java.util.Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(10_000, inTenant(tenant, () -> initial.get(set.id()).entries().size()));
    }

    @Test void concurrentCreatesCannotExceedTenantSetCapacity() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new ReferenceSetStore(persistence, mapper);
        inTenant(tenant, () -> {
            for (int index = 0; index < 195; index++) initial.add(ReferenceSet.of("set-" + index, "", List.of()));
            return null;
        });
        var read = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        java.util.function.Supplier<ReferenceSetStore> gatedList = () -> new ReferenceSetStore(persistence, mapper) {
            private final AtomicBoolean firstRead = new AtomicBoolean(true);
            @Override public List<ReferenceSet> list() {
                var result = super.list();
                if (firstRead.compareAndSet(true, false)) {
                    read.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
                return result;
            }
        };
        var first = gatedList.get();
        var second = gatedList.get();
        var setA = ReferenceSet.of("new-a", "", List.of());
        var setB = ReferenceSet.of("new-b", "", List.of());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.add(setA)));
            var b = pool.submit(() -> outcome(tenant, () -> second.add(setB)));
            try { assertTrue(read.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertEquals(java.util.Set.of(200, 400), java.util.Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(200, inTenant(tenant, () -> initial.list().size()));
    }

    private static int outcome(String tenant, Supplier<ReferenceSet> action) {
        try { inTenant(tenant, action); return 200; }
        catch (com.socp.platform.error.exception.ApiException failure) { return failure.getCode(); }
    }

    private ReferenceSetStore gated(CountDownLatch read, CountDownLatch release) {
        return new ReferenceSetStore(persistence, mapper) {
            private final AtomicBoolean firstRead = new AtomicBoolean(true);
            @Override public ReferenceSet get(String id) {
                ReferenceSet result = super.get(id);
                if (firstRead.compareAndSet(true, false)) {
                    read.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                }
                return result;
            }
        };
    }

    private static <T> T inTenant(String tenant, Supplier<T> action) {
        TenantContext.set(tenant);
        try { return action.get(); } finally { TenantContext.clear(); }
    }
}
