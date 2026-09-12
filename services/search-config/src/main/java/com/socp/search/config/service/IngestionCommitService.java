package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.persistence.store.SearchStore;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.service.port.IngestionPublicationTrigger;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.obs.web.TraceIdFilter;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomically persists canonical events and their Kafka publication intents. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class IngestionCommitService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final SearchEventRepository eventRepository;
    private final IngestionOutboxRepository outboxRepository;
    private final SearchStore searchStore;
    private final IngestionPublicationTrigger publicationTrigger;

    @Autowired
    public IngestionCommitService(SearchEventRepository eventRepository,
                                  IngestionOutboxRepository outboxRepository,
                                  SearchStore searchStore,
                                  ObjectProvider<IngestionPublicationTrigger> publicationTrigger) {
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.searchStore = searchStore;
        this.publicationTrigger = publicationTrigger == null ? null : publicationTrigger.getIfAvailable();
    }

    public IngestionCommitService(SearchEventRepository eventRepository,
                                  IngestionOutboxRepository outboxRepository,
                                  SearchStore searchStore) {
        this(eventRepository, outboxRepository, searchStore, null);
    }

    @Transactional
    public CommitResult commit(List<SearchEvent> events) {
        if (events == null || events.isEmpty()) return CommitResult.EMPTY;
        String tenant = TenantContext.require();
        String traceparent = TraceIdFilter.buildTraceparent();

        // Collapse exact duplicate identities inside one request before hitting
        // the database. Different content under the same identity is a client
        // contract violation, not a second event.
        Map<String, SearchEvent> distinct = new LinkedHashMap<>();
        for (SearchEvent event : events) {
            requireTenant(event, tenant);
            requireIdentity(event);
            SearchEvent previous = distinct.putIfAbsent(event.eventId(), event);
            if (previous != null && !sameContent(previous, event)) {
                throw new IngestionIdentityConflictException(event.eventId());
            }
        }

        List<String> eventIds = List.copyOf(distinct.keySet());
        Map<String, List<SearchEventEntity>> persistedById = groupEvents(
                eventRepository.findByTenantIdAndEventIdIn(tenant, eventIds));
        Map<String, List<IngestionOutboxEvent>> outboxById = groupOutbox(
                outboxRepository.findByTenantIdAndEventIdIn(tenant, eventIds));

        List<SearchEvent> newEvents = new ArrayList<>();
        List<SearchEvent> outboxEvents = new ArrayList<>();
        for (SearchEvent event : distinct.values()) {
            List<SearchEventEntity> persisted = persistedById.getOrDefault(event.eventId(), List.of());
            if (!persisted.isEmpty()) {
                assertPersistedCompatible(event, persisted);
            } else {
                newEvents.add(event);
            }

            List<IngestionOutboxEvent> existingOutbox =
                    outboxById.getOrDefault(event.eventId(), List.of());
            if (!existingOutbox.isEmpty()) {
                assertOutboxCompatible(event, existingOutbox);
            } else {
                // Repair a legacy event whose outbox row is missing, as well as
                // create the normal publication intent for a new event.
                outboxEvents.add(event);
            }
        }

        if (!newEvents.isEmpty()) {
            eventRepository.saveAll(newEvents.stream().map(SearchStore::toEntity).toList());
        }
        if (!outboxEvents.isEmpty()) {
            outboxRepository.saveAll(outboxEvents.stream().map(event -> IngestionOutboxEvent.pending(
                    event.eventId(),
                    DetectionRoutingKey.forSearchEvent(event.source(), event.host(), event.fields()),
                    serialize(event), traceparent)).toList());
        }

        Runnable afterCommitHook = () -> {
            if (!newEvents.isEmpty()) searchStore.rememberBatch(newEvents);
            if (publicationTrigger != null && !outboxEvents.isEmpty()) {
                publicationTrigger.triggerAsync();
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommitHook.run();
                }
            });
        } else {
            afterCommitHook.run();
        }
        return new CommitResult(events.size(), newEvents.size(), events.size() - newEvents.size(),
                Math.max(0, outboxEvents.size() - newEvents.size()));
    }

    private static void requireIdentity(SearchEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required for durable ingest");
        }
    }

    private static void requireTenant(SearchEvent event, String tenant) {
        if (event == null) return;
        String declared = event.tenantId();
        if (declared == null || declared.isBlank() || !tenant.equals(declared)) {
            throw new IllegalArgumentException("event tenant must match the authenticated tenant");
        }
    }

    private static boolean sameContent(SearchEvent left, SearchEvent right) {
        return IngestionEventIdentity.fingerprint(left).equals(IngestionEventIdentity.fingerprint(right));
    }

    private static void assertPersistedCompatible(SearchEvent incoming, List<SearchEventEntity> persisted) {
        String expected = IngestionEventIdentity.fingerprint(incoming);
        for (SearchEventEntity entity : persisted) {
            String actual = entity.getPayloadFingerprint();
            if (actual == null || actual.isBlank()) {
                actual = IngestionEventIdentity.fingerprint(SearchStore.fromEntity(entity));
            }
            if (!expected.equals(actual)) throw new IngestionIdentityConflictException(incoming.eventId());
        }
    }

    private static void assertOutboxCompatible(SearchEvent incoming, List<IngestionOutboxEvent> persisted) {
        String expected = IngestionEventIdentity.fingerprint(incoming);
        for (IngestionOutboxEvent outbox : persisted) {
            String actual = IngestionEventIdentity.fingerprintPayload(outbox.getPayload());
            if (!Objects.equals(expected, actual)) {
                throw new IngestionIdentityConflictException(incoming.eventId());
            }
        }
    }

    private static Map<String, List<SearchEventEntity>> groupEvents(Collection<SearchEventEntity> values) {
        Map<String, List<SearchEventEntity>> grouped = new HashMap<>();
        if (values != null) {
            for (SearchEventEntity value : values) {
                if (value != null && value.getEventId() != null) {
                    grouped.computeIfAbsent(value.getEventId(), ignored -> new ArrayList<>()).add(value);
                }
            }
        }
        return grouped;
    }

    private static Map<String, List<IngestionOutboxEvent>> groupOutbox(Collection<IngestionOutboxEvent> values) {
        Map<String, List<IngestionOutboxEvent>> grouped = new HashMap<>();
        if (values != null) {
            for (IngestionOutboxEvent value : values) {
                if (value != null && value.getEventId() != null) {
                    grouped.computeIfAbsent(value.getEventId(), ignored -> new ArrayList<>()).add(value);
                }
            }
        }
        return grouped;
    }

    private static String serialize(SearchEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot serialize canonical event " + event.eventId(), failure);
        }
    }

    /** Counts the request's durable acknowledgement and its idempotent no-ops. */
    public record CommitResult(int acknowledged, int created, int duplicates, int repairedOutbox) {
        private static final CommitResult EMPTY = new CommitResult(0, 0, 0, 0);
    }
}
