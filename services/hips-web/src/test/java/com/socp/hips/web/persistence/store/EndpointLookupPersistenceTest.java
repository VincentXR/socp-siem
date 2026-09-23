package com.socp.hips.web.persistence.store;

import com.socp.hips.web.domain.Endpoint;
import com.socp.hips.web.persistence.entity.EndpointEntity;
import com.socp.hips.web.persistence.repository.EndpointRepository;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(EndpointStore.class)
@TestPropertySource(properties = {"socp.demo-data.enabled=false", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EndpointLookupPersistenceTest {
    @Autowired private EndpointStore store;
    @Autowired private EndpointRepository repository;
    @Autowired private EntityManagerFactory factory;
    @Autowired private com.socp.hips.web.persistence.repository.EndpointEventRepository eventRepository;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void hostnameHistoryPagesBeyondGlobalPrefixWithoutCrossingTenants() {
        TenantContext.set("endpoint-history");
        var endpoint = store.save(Endpoint.register(" Shared-Host ", "203.0.113.7", "Linux", "agent"));
        assertThat(store.get(endpoint.id()).hostname()).isEqualTo(" Shared-Host ");
        var history = new EndpointEventStore(store, eventRepository, new com.fasterxml.jackson.databind.ObjectMapper());
        for (int index = 0; index < 505; index++) history.add(java.util.Map.of("hostname", "unrelated", "type", "process"));
        var rows = new ArrayList<com.socp.hips.web.persistence.entity.EndpointEventEntity>();
        for (int index = 0; index < 25; index++) {
            String id = String.format("history-%02d", index);
            rows.add(new com.socp.hips.web.persistence.entity.EndpointEventEntity(id, "endpoint-history",
                    "shared-HOST", Instant.EPOCH, "{\"eventId\":\"" + id + "\"}"));
        }
        eventRepository.saveAllAndFlush(rows);
        history.add(java.util.Map.of("hostname", "shared-host-extra"));
        history.add(java.util.Map.of("message", "without hostname"));
        assertThat(history.page(1, 200).getContent()).noneMatch(event -> String.valueOf(event.get("eventId")).startsWith("history-"));
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true); statistics.clear();
        try {
            var first = history.forHostname(endpoint.hostname(), 1, 20);
            assertThat(first.getTotalElements()).isEqualTo(25);
            assertThat(first.getContent()).hasSize(20);
            assertThat(statistics.getEntityLoadCount()).isEqualTo(20);
            assertThat(first.getContent().getFirst().get("eventId")).isEqualTo("history-00");
            assertThat(history.forHostname(endpoint.hostname(), 2, 20).getContent()).hasSize(5)
                    .first().extracting(event -> event.get("eventId")).isEqualTo("history-20");
        } finally { statistics.setStatisticsEnabled(enabled); }
        assertThat(history.forHostname("%", 1, 20)).isEmpty();
        assertThat(history.forHostname("", 1, 20)).isEmpty();
        TenantContext.set("endpoint-history-other");
        assertThat(store.get(endpoint.id())).isNull();
        assertThat(history.forHostname(endpoint.hostname(), 1, 20)).isEmpty();
    }

    @Test
    void lookupIsExactTenantScopedAndIndependentOfTheFirstInventoryPage() {
        TenantContext.set("related-tenant");
        var rows = new ArrayList<EndpointEntity>();
        for (int index = 0; index < 505; index++) {
            rows.add(new EndpointEntity(UUID.randomUUID().toString(), "irrelevant-" + index, "related-tenant",
                    "a-" + index, "10.0.0.1", "Linux", "agent", "ONLINE", Instant.now()));
        }
        repository.saveAllAndFlush(rows);
        Endpoint byIp = store.save(Endpoint.register("zz-ip", "203.0.113.7", "Linux", "agent"));
        Endpoint byName = store.save(Endpoint.register("zz-target", "203.0.113.8", "Linux", "agent"));
        Endpoint both = store.save(Endpoint.register("ZZ-TARGET", "203.0.113.7", "Linux", "agent"));
        store.save(Endpoint.register("zz-target-extra", "203.0.113.70", "Linux", "agent"));
        assertThat(store.page(1, 500, "").getContent()).extracting(Endpoint::id).doesNotContain(byIp.id(), byName.id());
        var first = store.related(1, 2, " 203.0.113.7 ", " zz-TARGET ");
        var second = store.related(2, 2, "203.0.113.7", "zz-target");
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);
        var found = new ArrayList<>(first.getContent());
        found.addAll(second.getContent());
        assertThat(found).extracting(Endpoint::id).containsExactlyInAnyOrder(byIp.id(), byName.id(), both.id());
        assertThat(store.related(1, 2, "203.0.113.7", "zz-target").getContent()).isEqualTo(first.getContent());
        assertThat(store.related(1, 20, "", "%")).isEmpty();
        assertThat(store.related(1, 20, "", "")).isEmpty();
        TenantContext.set("other-tenant");
        assertThat(store.related(1, 20, "203.0.113.7", "zz-target")).isEmpty();
    }

    @Test
    void matchingPagesHydrateOnlyTheirBoundedRows() {
        TenantContext.set("many-related");
        for (int index = 0; index < 25; index++) store.save(Endpoint.register("match-" + index, "203.0.113.99", "Linux", "agent"));
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var page = store.related(1, 20, "203.0.113.99", "");
            assertThat(page.getTotalElements()).isEqualTo(25);
            assertThat(page.getContent()).hasSize(20);
            assertThat(statistics.getEntityLoadCount()).isEqualTo(20);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
}
