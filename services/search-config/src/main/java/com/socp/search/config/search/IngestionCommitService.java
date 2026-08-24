package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.obs.TraceIdFilter;
import com.socp.rule.partition.DetectionRoutingKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Atomically persists canonical events and their Kafka publication intents. */
@Service
public class IngestionCommitService {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final SearchEventRepository eventRepository;
    private final IngestionOutboxRepository outboxRepository;
    private final SearchStore searchStore;
    private final IngestionOutboxPublisher outboxPublisher;

    @Autowired
    public IngestionCommitService(SearchEventRepository eventRepository,
                                  IngestionOutboxRepository outboxRepository,
                                  SearchStore searchStore,
                                  IngestionOutboxPublisher outboxPublisher) {
        this.eventRepository = eventRepository;
        this.outboxRepository = outboxRepository;
        this.searchStore = searchStore;
        this.outboxPublisher = outboxPublisher;
    }

    public IngestionCommitService(SearchEventRepository eventRepository,
                                  IngestionOutboxRepository outboxRepository,
                                  SearchStore searchStore) {
        this(eventRepository, outboxRepository, searchStore, null);
    }

    @Transactional
    public void commit(List<SearchEvent> events) {
        if (events == null || events.isEmpty()) return;
        String traceparent = TraceIdFilter.buildTraceparent();
        eventRepository.saveAll(events.stream().map(SearchStore::toEntity).toList());
        outboxRepository.saveAll(events.stream().map(event -> IngestionOutboxEvent.pending(
                event.eventId(),
                DetectionRoutingKey.forSearchEvent(event.source(), event.host(), event.fields()),
                serialize(event), traceparent)).toList());

        Runnable afterCommitHook = () -> {
            searchStore.rememberBatch(events);
            if (outboxPublisher != null) {
                outboxPublisher.triggerAsync();
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
    }

    private static String serialize(SearchEvent event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot serialize canonical event " + event.eventId(), failure);
        }
    }
}
