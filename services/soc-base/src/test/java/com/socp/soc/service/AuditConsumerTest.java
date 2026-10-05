package com.socp.soc.service;

import com.socp.soc.persistence.entity.AuditEntity;
import com.socp.soc.persistence.repository.AuditRepository;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditConsumerTest {

    @Test
    void persistsObjectAndRedactedChangeMetadataFromTheAuditEnvelope() throws Exception {
        AuditConsumer consumer = new AuditConsumer(mock(AuditRepository.class));
        String raw = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "eventId", "details-1", "tenantId", "tenant-a", "action", "ASSIGN_INCIDENT",
                "operator", "alice", "target", "case", "entityId", "case-42", "traceId", "trace-1",
                "changeSummary", "{\"assignee\":\"bob\"}"));
        AuditEntity entity = consumer.parse("k", raw);
        assertEquals("case-42", entity.getEntityId());
        assertEquals("trace-1", entity.getTraceId());
        org.assertj.core.api.Assertions.assertThat(entity.getChangeSummary()).contains("bob");
    }

    @Test
    void parsesStableIdentityAndTenant() throws Exception {
        AuditConsumer consumer = new AuditConsumer(mock(AuditRepository.class));

        AuditEntity entity = consumer.parse("kafka-key", """
                {"eventId":"event-1","tenantId":"tenant-a","action":"CREATE_RULE",
                 "operator":"alice","target":"rule-7","result":"SUCCESS",
                 "timestamp":"2026-08-23T00:00:00Z"}
                """);

        assertEquals("event-1", entity.getEventId());
        assertEquals("tenant-a", entity.getTenantId());
        assertEquals("alice", entity.getOperator());
        assertEquals(Instant.parse("2026-08-23T00:00:00Z"), entity.getTs());
    }

    @Test
    void duplicateEventIdIsPersistedOnlyOnce() {
        AuditRepository repository = mock(AuditRepository.class);
        when(repository.existsByTenantIdAndEventId("tenant-a", "event-1")).thenReturn(false, true);
        AuditConsumer consumer = new AuditConsumer(repository);
        String raw = "{\"eventId\":\"event-1\",\"tenantId\":\"tenant-a\",\"action\":\"CREATE\"}";

        consumer.processRecord("event-1", raw);
        consumer.processRecord("event-1", raw);

        verify(repository, times(1)).saveAndFlush(any(AuditEntity.class));
    }

    @Test
    void malformedTenantIsRejectedBeforePersistence() {
        AuditRepository repository = mock(AuditRepository.class);
        AuditConsumer consumer = new AuditConsumer(repository);

        assertThrows(AuditConsumer.InvalidAuditEventException.class,
                () -> consumer.processRecord("event-2",
                        "{\"tenantId\":\"../other\",\"action\":\"CREATE\"}"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void restartsConsumerSessionAfterUnexpectedFailure() {
        AuditConsumer consumer = new AuditConsumer(mock(AuditRepository.class));
        AtomicInteger sessions = new AtomicInteger();
        AtomicInteger pauses = new AtomicInteger();

        consumer.runLoop(() -> {
            if (sessions.incrementAndGet() == 1) throw new IllegalStateException("broker restart");
        }, ignored -> {
            pauses.incrementAndGet();
            return true;
        });

        assertEquals(2, sessions.get());
        assertEquals(1, pauses.get());
    }

    @Test
    void interruptionStopsRestartWithoutSleeping() {
        AuditConsumer consumer = new AuditConsumer(mock(AuditRepository.class));
        AtomicInteger pauses = new AtomicInteger();
        try {
            consumer.runLoop(() -> {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("shutdown");
            }, ignored -> {
                pauses.incrementAndGet();
                return true;
            });
            assertEquals(0, pauses.get());
        } finally {
            Thread.interrupted();
        }
    }
}
