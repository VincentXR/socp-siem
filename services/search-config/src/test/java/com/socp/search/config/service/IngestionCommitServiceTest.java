package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import com.socp.search.config.persistence.store.SearchStore;
import com.socp.platform.tenant.context.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestionCommitServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void cleanupSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TenantContext.clear();
    }

    @Test
    void persistsEventAndOutboxBeforeUpdatingTheSearchWindowAfterCommit() {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent event = new SearchEvent("event-1", Instant.parse("2026-08-21T00:00:00Z"),
                "auth", "host-1", "HIGH", "failure",
                Map.of("tenant_id", "default", "user", "alice"), Map.of());

        TransactionSynchronizationManager.initSynchronization();
        service.commit(List.of(event));

        verify(events).saveAll(anyList());
        verify(outbox).saveAll(anyList());
        verify(store, never()).rememberBatch(anyList());

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(store).rememberBatch(List.of(event));
    }

    @Test
    void refusesAnEventDeclaredForAnotherTenantBeforeAnyRepositoryRead() {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent foreign = new SearchEvent("foreign-event", Instant.EPOCH,
                "auth", "host-1", "HIGH", "failure",
                Map.of("tenant_id", "tenant-b"), Map.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.commit(List.of(foreign)));

        assertEquals("event tenant must match the authenticated tenant", failure.getMessage());
        verifyNoInteractions(events, outbox, store);
    }

    @Test
    void retriesAreAcknowledgedWithoutCreatingAnotherEventOrOutbox() throws Exception {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent event = event("event-retry", "failure");

        SearchEventEntity persisted = new SearchEventEntity();
        persisted.setEventId(event.eventId());
        persisted.setPayloadFingerprint(IngestionEventIdentity.fingerprint(event));
        IngestionOutboxEvent publication = IngestionOutboxEvent.pending(
                event.eventId(), "auth|host-1", serialize(event), "trace");
        publication.setTenantId("default");
        when(events.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of(persisted));
        when(outbox.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of(publication));

        IngestionCommitService.CommitResult result = service.commit(List.of(event));

        assertEquals(1, result.acknowledged());
        assertEquals(0, result.created());
        assertEquals(1, result.duplicates());
        verify(events, never()).saveAll(anyList());
        verify(outbox, never()).saveAll(anyList());
        verify(store, never()).rememberBatch(anyList());
    }

    @Test
    void retriesAcceptEquivalentOutboxPayloadWithDifferentFieldOrder() throws Exception {
        SearchEvent incoming = eventWithFields("event-retry-order", "failure",
                Map.of("tenant_id", "default", "user", "alice"));
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("user", "alice");
        reordered.put("tenant_id", "default");
        SearchEvent persistedPayload = eventWithFields("event-retry-order", "failure", reordered);

        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEventEntity persisted = new SearchEventEntity();
        persisted.setEventId(incoming.eventId());
        persisted.setPayloadFingerprint(IngestionEventIdentity.fingerprint(incoming));
        IngestionOutboxEvent publication = IngestionOutboxEvent.pending(
                incoming.eventId(), "auth|host-1", serialize(persistedPayload), "trace");
        publication.setTenantId("default");
        when(events.findByTenantIdAndEventIdIn("default", List.of(incoming.eventId())))
                .thenReturn(List.of(persisted));
        when(outbox.findByTenantIdAndEventIdIn("default", List.of(incoming.eventId())))
                .thenReturn(List.of(publication));

        IngestionCommitService.CommitResult result = service.commit(List.of(incoming));

        assertEquals(1, result.acknowledged());
        assertEquals(1, result.duplicates());
        verify(events, never()).saveAll(anyList());
        verify(outbox, never()).saveAll(anyList());
    }

    @Test
    void rejectsCorruptOutboxPayloadInsteadOfAcknowledgingRetry() {
        SearchRepositoryFixture fixture = new SearchRepositoryFixture();
        SearchEvent event = event("event-corrupt-outbox", "failure");
        SearchEventEntity persisted = new SearchEventEntity();
        persisted.setEventId(event.eventId());
        persisted.setPayloadFingerprint(IngestionEventIdentity.fingerprint(event));
        IngestionOutboxEvent publication = IngestionOutboxEvent.pending(
                event.eventId(), "auth|host-1", "{not-json", "trace");
        publication.setTenantId("default");
        when(fixture.events.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of(persisted));
        when(fixture.outbox.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of(publication));

        assertThrows(IngestionIdentityConflictException.class,
                () -> fixture.service.commit(List.of(event)));
        verify(fixture.events, never()).saveAll(anyList());
        verify(fixture.outbox, never()).saveAll(anyList());
    }

    @Test
    void rejectsIdentityReuseWithDifferentContentBeforeWritingEitherSide() {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent original = event("event-conflict", "failure");
        SearchEvent changed = event("event-conflict", "changed payload");
        SearchEventEntity persisted = new SearchEventEntity();
        persisted.setEventId(original.eventId());
        persisted.setPayloadFingerprint(IngestionEventIdentity.fingerprint(original));
        when(events.findByTenantIdAndEventIdIn("default", List.of(original.eventId())))
                .thenReturn(List.of(persisted));
        when(outbox.findByTenantIdAndEventIdIn("default", List.of(original.eventId())))
                .thenReturn(List.of());

        assertThrows(IngestionIdentityConflictException.class,
                () -> service.commit(List.of(changed)));
        verify(events, never()).saveAll(anyList());
        verify(outbox, never()).saveAll(anyList());
    }

    @Test
    void collapsesExactDuplicateIdentitiesInsideOneTransaction() {
        SearchEventRepository events = mock(SearchEventRepository.class);
        IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        SearchStore store = mock(SearchStore.class);
        IngestionCommitService service = new IngestionCommitService(events, outbox, store);
        SearchEvent event = event("event-in-batch", "same");
        when(events.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of());
        when(outbox.findByTenantIdAndEventIdIn("default", List.of(event.eventId())))
                .thenReturn(List.of());

        IngestionCommitService.CommitResult result = service.commit(List.of(event, event));

        assertEquals(2, result.acknowledged());
        assertEquals(1, result.created());
        assertEquals(1, result.duplicates());
        verify(events).saveAll(anyList());
        verify(outbox).saveAll(anyList());
    }

    private static SearchEvent event(String id, String message) {
        return eventWithFields(id, message, Map.of("tenant_id", "default", "user", "alice"));
    }

    private static SearchEvent eventWithFields(String id, String message, Map<String, String> fields) {
        return new SearchEvent(id, Instant.parse("2026-08-21T00:00:00Z"),
                "auth", "host-1", "HIGH", message, fields, Map.of());
    }

    private static String serialize(SearchEvent event) throws Exception {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .writeValueAsString(event);
    }

    private static final class SearchRepositoryFixture {
        private final SearchEventRepository events = mock(SearchEventRepository.class);
        private final IngestionOutboxRepository outbox = mock(IngestionOutboxRepository.class);
        private final SearchStore store = mock(SearchStore.class);
        private final IngestionCommitService service = new IngestionCommitService(events, outbox, store);
    }
}
