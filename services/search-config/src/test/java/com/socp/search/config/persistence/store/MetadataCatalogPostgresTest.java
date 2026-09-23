package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.FieldDef;
import com.socp.search.config.domain.LogCategory;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import(TenantCatalogPersistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class MetadataCatalogPostgresTest {
    @Container
    static final GenericContainer<?> DATABASE = new GenericContainer<>(MiddlewareImages.postgres())
            .withEnv("POSTGRES_DB", "metadata").withEnv("POSTGRES_USER", "socp")
            .withEnv("POSTGRES_PASSWORD", "socp-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + DATABASE.getHost()
                + ":" + DATABASE.getMappedPort(5432) + "/metadata");
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired TenantCatalogPersistence persistence;
    @Autowired TenantCatalogEntryRepository entries;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void stableBuiltinsAndLegacyOverlaysRemainTenantScopedAcrossInstances() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var aTypes = new DataSourceTypeStore(persistence, mapper);
        var bTypes = new DataSourceTypeStore(persistence, mapper);
        var aCategories = new LogCategoryStore(persistence, mapper);
        var bCategories = new LogCategoryStore(persistence, mapper);
        var aFields = new FieldDefStore(persistence, mapper);
        var bFields = new FieldDefStore(persistence, mapper);
        assertEquals(inTenant(tenant, () -> aTypes.list().stream().map(DataSourceType::id).toList()),
                inTenant(tenant, () -> bTypes.list().stream().map(DataSourceType::id).toList()));
        assertEquals(inTenant(tenant, () -> aCategories.list().stream().map(LogCategory::id).toList()),
                inTenant(tenant, () -> bCategories.list().stream().map(LogCategory::id).toList()));
        assertEquals(inTenant(tenant, () -> aFields.list().stream().map(FieldDef::id).toList()),
                inTenant(tenant, () -> bFields.list().stream().map(FieldDef::id).toList()));

        var legacyType = new DataSourceType(UUID.randomUUID().toString(), "SYSLOG", "Legacy", "", true, java.time.Instant.now());
        var legacyCategory = new LogCategory(UUID.randomUUID().toString(), "AUTH", "Legacy", "", "HIGH", true, java.time.Instant.now());
        var legacyField = new FieldDef(UUID.randomUUID().toString(), "user", "Legacy", "string", "parse", true, true, true, "", java.time.Instant.now());
        inTenant(tenant, () -> {
            try {
                persistence.save("data_source_type", tenant, legacyType.id(), mapper.writeValueAsString(legacyType));
                persistence.save("log_category", tenant, legacyCategory.id(), mapper.writeValueAsString(legacyCategory));
                persistence.save("field_def", tenant, legacyField.id(), mapper.writeValueAsString(legacyField));
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            return null;
        });
        assertEquals(legacyType.id(), inTenant(tenant, () -> bTypes.list().stream()
                .filter(item -> "SYSLOG".equals(item.code())).findFirst().orElseThrow().id()));
        assertEquals(legacyCategory.id(), inTenant(tenant, () -> bCategories.list().stream()
                .filter(item -> "AUTH".equals(item.code())).findFirst().orElseThrow().id()));
        assertEquals(legacyField.id(), inTenant(tenant, () -> bFields.list().stream()
                .filter(item -> "user".equals(item.fieldName())).findFirst().orElseThrow().id()));
        assertEquals(1, inTenant(tenant, () -> bTypes.list().stream().filter(item -> "SYSLOG".equals(item.code())).count()));
        assertEquals(1, inTenant(tenant, () -> bCategories.list().stream().filter(item -> "AUTH".equals(item.code())).count()));
        assertEquals(1, inTenant(tenant, () -> bFields.list().stream().filter(item -> "user".equals(item.fieldName())).count()));
        assertNotEquals(legacyType.id(), inTenant(tenant + "-other", () -> bTypes.list().stream()
                .filter(item -> "SYSLOG".equals(item.code())).findFirst().orElseThrow().id()));
    }

    @Test void simultaneousCreatorsCannotPublishTheSameCode() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var reads = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var gated = gatedList("data_source_type", reads, release);
        var first = new DataSourceTypeStore(gated, mapper);
        var second = new DataSourceTypeStore(gated, mapper);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.save(DataSourceType.create("DUPLICATE", "first", "", true))));
            var b = pool.submit(() -> outcome(tenant, () -> second.save(DataSourceType.create("duplicate", "second", "", true))));
            try { assertTrue(reads.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertEquals(Set.of(200, 409), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(1, inTenant(tenant, () -> new DataSourceTypeStore(persistence, mapper).list().stream()
                .filter(item -> "DUPLICATE".equalsIgnoreCase(item.code())).count()));
    }

    @Test void simultaneousCreatorsCannotExceedTheTenantLimit() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new DataSourceTypeStore(persistence, mapper);
        inTenant(tenant, () -> {
            for (int index = 0; index < 118; index++) initial.save(DataSourceType.create("type-" + index, "name", "", true));
            return null;
        });
        assertEquals(127, inTenant(tenant, () -> initial.list().size()));
        var reads = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var gated = gatedList("data_source_type", reads, release);
        var first = new DataSourceTypeStore(gated, mapper);
        var second = new DataSourceTypeStore(gated, mapper);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.save(DataSourceType.create("last-a", "a", "", true))));
            var b = pool.submit(() -> outcome(tenant, () -> second.save(DataSourceType.create("last-b", "b", "", true))));
            try { assertTrue(reads.await(10, TimeUnit.SECONDS)); } finally { release.countDown(); }
            assertEquals(Set.of(200, 400), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(128, inTenant(tenant, () -> initial.list().size()));
    }

    @Test void staleUpdateCannotResurrectDeletedMetadata() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new DataSourceTypeStore(persistence, mapper);
        var created = inTenant(tenant, () -> initial.save(DataSourceType.create("DELETE_ME", "original", "", true)));
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gated = new TenantCatalogPersistence(entries, transactions) {
            private final AtomicBoolean first = new AtomicBoolean(true);
            @Override StoredEntry find(String type, String tenantId, String itemId) {
                StoredEntry found = super.find(type, tenantId, itemId);
                if (type.equals("data_source_type") && itemId.equals(created.id())
                        && first.compareAndSet(true, false)) await(read, release);
                return found;
            }
        };
        var oldWriter = new DataSourceTypeStore(gated, mapper);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var update = pool.submit(() -> outcome(tenant, () -> oldWriter.update(created.id(),
                    new DataSourceType(created.id(), created.code(), "late", "", true, null))));
            try {
                assertTrue(read.await(10, TimeUnit.SECONDS));
                assertTrue(inTenant(tenant, () -> initial.delete(created.id())));
            } finally { release.countDown(); }
            assertEquals(404, update.get(15, TimeUnit.SECONDS));
        }
        assertNull(inTenant(tenant, () -> initial.get(created.id())));
    }

    private TenantCatalogPersistence gatedList(String catalogType, CountDownLatch read, CountDownLatch release) {
        return new TenantCatalogPersistence(entries, transactions) {
            private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            @Override List<StoredEntry> list(String type, String tenantId) {
                List<StoredEntry> result = super.list(type, tenantId);
                if (catalogType.equals(type) && calls.incrementAndGet() <= 2) await(read, release);
                return result;
            }
        };
    }

    private static void await(CountDownLatch read, CountDownLatch release) {
        read.countDown();
        try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private static int outcome(String tenant, Supplier<?> operation) {
        try { inTenant(tenant, operation); return 200; }
        catch (ApiException failure) { return failure.getCode(); }
    }

    private static <T> T inTenant(String tenant, Supplier<T> operation) {
        TenantContext.set(tenant);
        try { return operation.get(); } finally { TenantContext.clear(); }
    }
}
