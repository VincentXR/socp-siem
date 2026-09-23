package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.persistence.repository.LogSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class LogSourceCataloguePostgresTest {
    @Container
    static final GenericContainer<?> DATABASE = new GenericContainer<>(MiddlewareImages.postgres())
            .withEnv("POSTGRES_DB", "sources").withEnv("POSTGRES_USER", "socp")
            .withEnv("POSTGRES_PASSWORD", "socp-test").withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\n", 2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + DATABASE.getHost()
                + ":" + DATABASE.getMappedPort(5432) + "/sources");
        registry.add("spring.datasource.username", () -> "socp");
        registry.add("spring.datasource.password", () -> "socp-test");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired LogSourceRepository repository;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void sourceNameSearchPaginatesInStableOrderWithinTheCurrentTenant() {
        String tenantA = UUID.randomUUID().toString();
        String tenantB = UUID.randomUUID().toString();
        LogSourceStore store = new LogSourceStore(repository);
        TenantContext.set(tenantA);
        store.save(source("auth-log"));
        store.save(source("100% Auth"));
        store.save(source("unrelated"));
        TenantContext.set(tenantB);
        store.save(source("auth-other-tenant"));

        TenantContext.set(tenantA);
        var first = store.pageByName("AUTH", PageRequest.of(0, 1, Sort.by("sourceId")));
        var second = store.pageByName("AUTH", PageRequest.of(1, 1, Sort.by("sourceId")));
        assertEquals(2, first.getTotalElements());
        assertEquals(2, second.getTotalElements());
        List<String> ids = List.of(first.getContent().getFirst().id(), second.getContent().getFirst().id());
        assertTrue(ids.get(0).compareTo(ids.get(1)) < 0);
        assertEquals(2, ids.stream().distinct().count());
        assertEquals(List.of("100% Auth"), store.pageByName("%", PageRequest.of(0, 10))
                .getContent().stream().map(LogSource::name).toList());

        TenantContext.set(tenantB);
        assertEquals(List.of("auth-other-tenant"), store.pageByName("auth", PageRequest.of(0, 10))
                .getContent().stream().map(LogSource::name).toList());
    }

    private static LogSource source(String name) {
        return LogSource.create(name, SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "test", true);
    }
}
