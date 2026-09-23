package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.ParseRule;
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
class ParseRuleQuotaPostgresTest {
    @Container
    static final GenericContainer<?> DATABASE = new GenericContainer<>(MiddlewareImages.postgres())
            .withEnv("POSTGRES_DB", "parse_rules").withEnv("POSTGRES_USER", "socp")
            .withEnv("POSTGRES_PASSWORD", "socp-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + DATABASE.getHost()
                + ":" + DATABASE.getMappedPort(5432) + "/parse_rules");
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
    void twoReplicasCannotExceedTheGlobalRuleLimit() throws Exception {
        String tenant = UUID.randomUUID().toString();
        var initial = new ParseRuleStore(persistence, mapper);
        inTenant(tenant, () -> {
            for (int index = 0; index < 29; index++) initial.create(rule("global-" + index));
            return null;
        });
        assertEquals(31, inTenant(tenant, () -> initial.list().size()));

        CountDownLatch reads = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        var gated = new TenantCatalogPersistence(entries, transactions) {
            private final AtomicInteger calls = new AtomicInteger();
            @Override List<StoredEntry> list(String type, String tenantId) {
                List<StoredEntry> result = super.list(type, tenantId);
                if ("parse_rule".equals(type) && calls.incrementAndGet() <= 2) {
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
        var first = new ParseRuleStore(gated, mapper);
        var second = new ParseRuleStore(gated, mapper);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> outcome(tenant, () -> first.create(rule("last-a"))));
            var b = pool.submit(() -> outcome(tenant, () -> second.create(rule("last-b"))));
            try { assertTrue(reads.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(Set.of(200, 400), Set.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)));
        }
        assertEquals(32, inTenant(tenant, () -> initial.list().size()));
        assertEquals(2, inTenant(tenant + "-other", () -> initial.list().size()));
    }

    @Test
    void bulkSelectedRulesHonorTenantAndTemplateTombstones() {
        String tenant = UUID.randomUUID().toString();
        var store = new ParseRuleStore(persistence, mapper);
        inTenant(tenant, () -> {
            store.create(ParseRule.createWithId("scoped", "Scoped rule", "source-a", "KV", null,
                    List.of(), List.of(), true, 10));
            return null;
        });
        assertEquals(List.of("scoped", "sshd-auth-failed"), inTenant(tenant, () -> store.getMany(
                List.of("scoped", "missing", "sshd-auth-failed"))).stream().map(ParseRule::id).toList());
        assertTrue(inTenant(tenant, () -> store.delete("sshd-auth-failed")));
        assertEquals(List.of("scoped"), inTenant(tenant, () -> store.getMany(
                List.of("scoped", "sshd-auth-failed"))).stream().map(ParseRule::id).toList());
        assertEquals(List.of("sshd-auth-failed"), inTenant(tenant + "-other", () -> store.getMany(
                List.of("scoped", "sshd-auth-failed"))).stream().map(ParseRule::id).toList());
    }

    @Test
    void deletingOwnedRulesDoesNotAccumulateTombstones() {
        String tenant = UUID.randomUUID().toString();
        var store = new ParseRuleStore(persistence, mapper);

        inTenant(tenant, () -> {
            store.create(rule("owned"));
            assertTrue(store.delete("owned"));
            assertTrue(entries.findByCatalogTypeAndTenantIdAndItemId("parse_rule", tenant, "owned").isEmpty());
            store.create(rule("owned"));
            assertTrue(store.delete("owned"));
            assertTrue(entries.findByCatalogTypeAndTenantIdAndItemId("parse_rule", tenant, "owned").isEmpty());

            assertTrue(store.delete("nginx-attack"));
            assertTrue(entries.findByCatalogTypeAndTenantIdAndItemId(
                    "parse_rule", tenant, "nginx-attack").orElseThrow().isDeleted());
            return null;
        });
    }

    private static ParseRule rule(String id) {
        return ParseRule.createWithId(id, id, null, "KV", null,
                List.of(), List.of(), true, 10);
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
