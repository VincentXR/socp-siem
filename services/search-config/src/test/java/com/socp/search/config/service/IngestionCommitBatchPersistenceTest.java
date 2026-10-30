package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import com.socp.search.config.persistence.store.SearchStore;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({IngestionCommitService.class, IngestionCommitBatchPersistenceTest.Config.class})
class IngestionCommitBatchPersistenceTest {
    @TestConfiguration
    static class Config {
        @Bean SearchStore searchStore() { return mock(SearchStore.class); }
    }

    @Autowired IngestionCommitService commits;
    @Autowired IngestionOutboxRepository outbox;
    @Autowired SearchEventRepository canonical;
    @Autowired SearchStore cache;
    @Autowired EntityManagerFactory factory;
    @Autowired JdbcTemplate jdbc;
    String tenant;

    @BeforeEach void prepare() {
        tenant = "batch-" + UUID.randomUUID();
        TenantContext.set(tenant);
        reset(cache);
    }

    @AfterEach void cleanup() {
        jdbc.update("delete from t_ingestion_outbox where tenant_id = ?", tenant);
        jdbc.update("delete from t_search_event where tenant_id = ?", tenant);
        TenantContext.clear();
    }

    @Test void batchAvoidsPerRowMergeSelectsAndKeepsReplayIdentity() {
        var statistics = factory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        List<SearchEvent> events = events();
        statistics.clear();
        assertEquals(128, commits.commit(events).created());
        // Two bulk identity lookups and two batched INSERT statements. Allow
        // provider bookkeeping, but reject O(events) merge SELECTs or INSERTs.
        assertTrue(statistics.getPrepareStatementCount() <= 10,
                "prepared statements: " + statistics.getPrepareStatementCount());
        var persisted = outbox.findByTenantId(tenant);
        assertEquals(128, persisted.size());
        assertTrue(persisted.stream().noneMatch(row -> row.isNew()));
        assertTrue(persisted.stream().allMatch(row -> row.getSequenceNo() != null));
        assertEquals(128, commits.commit(events).duplicates());
        assertEquals(128, outbox.findByTenantId(tenant).size());
        verify(cache, times(1)).rememberBatch(anyList());
    }

    @Test void failureInOutboxBatchRollsBackCanonicalEventsAndDoesNotPopulateCache() {
        jdbc.execute("alter table t_ingestion_outbox add constraint reject_batch_fixture check (event_id <> 'batch-event-127')");
        try {
            assertThrows(RuntimeException.class, () -> commits.commit(events()));
            assertEquals(0, jdbc.queryForObject("select count(*) from t_search_event where tenant_id = ?", Integer.class, tenant));
            assertEquals(0, jdbc.queryForObject("select count(*) from t_ingestion_outbox where tenant_id = ?", Integer.class, tenant));
            verifyNoInteractions(cache);
        } finally {
            jdbc.execute("alter table t_ingestion_outbox drop constraint reject_batch_fixture");
        }
    }

    @Test void bulkIdentityQueriesKeepRequestedRowsTenantIsolationAndConflictChecks() {
        List<SearchEvent> events = events();
        String otherTenant = tenant + "-other";
        assertEquals(128, commits.commit(events).created());
        try {
            try (var scope = TenantContext.open(otherTenant)) {
                assertEquals(128, commits.commit(events.stream().map(event -> new SearchEvent(event.eventId(),
                        event.timestamp(), event.source(), event.host(), event.severity(), "other tenant payload",
                        Map.of("tenant_id", otherTenant), Map.of())).toList()).created());
            }
            var requested = List.of("batch-event-0", "batch-event-63", "batch-event-127", "missing", "batch-event-0");
            var expected = java.util.Set.of("batch-event-0", "batch-event-63", "batch-event-127");
            var stored = canonical.findByTenantIdAndEventIdIn(tenant, requested);
            var intents = outbox.findByTenantIdAndEventIdIn(tenant, requested);
            assertEquals(3, stored.size());
            assertEquals(3, intents.size());
            assertEquals(expected, stored.stream().map(row -> row.getEventId()).collect(java.util.stream.Collectors.toSet()));
            assertEquals(expected, intents.stream().map(row -> row.getEventId()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(stored.stream().allMatch(row -> tenant.equals(row.getTenantId()) && row.getMsg().startsWith("event ")));
            assertTrue(intents.stream().allMatch(row -> tenant.equals(row.getTenantId()) && row.getPayload().contains("event ")));
            assertTrue(canonical.findByTenantIdAndEventIdIn(tenant, List.of()).isEmpty());
            assertTrue(outbox.findByTenantIdAndEventIdIn(tenant, List.of()).isEmpty());
            assertEquals(128, commits.commit(events).duplicates());

            SearchEvent original = events.getFirst();
            var conflicting = new SearchEvent(original.eventId(), original.timestamp(), original.source(), original.host(),
                    original.severity(), "conflicting content", original.fields(), original.ecs());
            assertThrows(IngestionIdentityConflictException.class, () -> commits.commit(List.of(conflicting)));
            // Canonical compatibility must not hide a conflicting publication intent.
            jdbc.update("update t_ingestion_outbox set payload = ? where tenant_id = ? and event_id = ?",
                    "{\"msg\":\"conflicting outbox\"}", tenant, original.eventId());
            assertThrows(IngestionIdentityConflictException.class, () -> commits.commit(List.of(original)));
            assertEquals(128, canonical.countByTenantId(tenant));
            assertEquals(128, outbox.findByTenantId(tenant).size());
        } finally {
            jdbc.update("delete from t_ingestion_outbox where tenant_id = ?", otherTenant);
            jdbc.update("delete from t_search_event where tenant_id = ?", otherTenant);
        }
    }

    private List<SearchEvent> events() {
        return IntStream.range(0, 128).mapToObj(index -> new SearchEvent("batch-event-" + index,
                Instant.parse("2026-10-29T00:00:00Z"), "test", "host-" + index, "INFO", "event " + index,
                Map.of("tenant_id", tenant), Map.of())).toList();
    }
}
