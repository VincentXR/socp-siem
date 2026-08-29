package com.socp.soc.service;

import com.socp.soc.persistence.entity.AuditEntity;
import com.socp.soc.persistence.repository.AuditRepository;

import org.junit.jupiter.api.Test;

import java.time.Instant;

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
}
