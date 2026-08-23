package com.socp.platform.audit;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaAuditSinkTest {

    @Test
    void publishesWithStableEventIdAsKafkaKey() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        AuditRecord record = new AuditRecord("event-1", "tenant-a", "CREATE", "alice",
                "rule", "SUCCESS", Instant.parse("2026-08-23T00:00:00Z"));
        when(template.send(eq("socp-audit"), eq("event-1"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new KafkaAuditSink(template, "socp-audit", true).publish(record);

        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("socp-audit"), eq("event-1"), payload.capture());
        assertTrue(payload.getValue().contains("\"eventId\":\"event-1\""));
        assertTrue(payload.getValue().contains("\"timestamp\":\"2026-08-23T00:00:00Z\""));
    }

    @Test
    void failurePolicyCanBeFailClosedOrBestEffort() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        AuditRecord record = new AuditRecord("event-2", "tenant-a", "DELETE", "alice",
                "rule", "SUCCESS", Instant.EPOCH);
        when(template.send(eq("audit"), eq("event-2"), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

        assertThrows(IllegalStateException.class,
                () -> new KafkaAuditSink(template, "audit", true).publish(record));
        assertDoesNotThrow(() -> new KafkaAuditSink(template, "audit", false).publish(record));
    }
}
