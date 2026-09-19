package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.rule.model.SecurityEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class DetectionRecordProcessorTest {

    private static final String EVENT_PAYLOAD = """
            {"eventId":"evt-terminal","tenantId":"default","source":"auth","host":"web-1",\
            "msg":"login failed","fields":{"src_ip":"198.51.100.10"}}
            """;

    @Test
    void parsesCanonicalFieldsWithoutUncheckedMaps() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.NormalizedDetectionRecord record = processor.parse(
                "ignored", """
                        {"eventId":"evt-1","tenantId":"default","timestamp":"2026-08-23T00:00:00Z",
                         "source":"auth","host":"web-1","severity":"high","msg":"login failed",
                         "fields":{"src_ip":"198.51.100.10","attempts":3}}
                        """);

        assertEquals("evt-1", record.event().id());
        assertEquals("HIGH", record.event().severity().name());
        assertEquals("3", record.event().fields().get("attempts"));
        assertTrue(record.routingKey().contains("198.51.100.10"));
    }

    @Test
    void bridgesEcsFieldsIntoDetectionRuleFieldMap() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.NormalizedDetectionRecord record = processor.parse(
                "ignored", """
                        {"eventId":"evt-ecs","tenantId":"default","timestamp":"2026-08-23T00:00:00Z",
                         "source":"auth","host":"web-1","severity":"high","msg":"login failed",
                         "fields":{"src_ip":"198.51.100.10"},
                         "ecs":{"event.category":"authentication","source.ip":"198.51.100.10"}}
                        """);

        assertEquals("authentication", record.event().fields().get("event.category"));
        assertEquals("198.51.100.10", record.event().fields().get("source.ip"));
    }

    @Test
    void rejectsNonObjectEcsAsTerminalPayload() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        assertThrows(DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", "{\"eventId\":\"evt-bad-ecs\",\"tenantId\":\"default\",\"ecs\":[]}"));
    }

    @Test
    void rejectsNonObjectFieldsAsTerminalPayload() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.MalformedDetectionRecordException error = assertThrows(
                DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", "{\"eventId\":\"evt-bad\",\"fields\":[]}"));

        assertEquals("evt-bad", error.eventId());
    }

    @Test
    void rejectsMissingTenantAsTerminalPayload() {
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                mock(DetectEngineService.class), new InMemoryDetectionStateStore(), null);

        assertThrows(DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", "{\"eventId\":\"evt-no-tenant\",\"fields\":{}}"));
    }

    @Test
    void aDurableResultFailureCarriesTheNormalizedIdentityAndTenant() {
        DetectEngineService engine = mock(DetectEngineService.class);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                engine, new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.TerminalDetectionFailure failure = assertThrows(
                DetectionRecordProcessor.TerminalDetectionFailure.class,
                () -> processor.process("socp-events", 2, 42L, "default|src_ip|198.51.100.10",
                        EVENT_PAYLOAD));

        // The Kafka routing key is shared by every event of one entity, so the
        // terminal identity must come from the event itself.
        assertEquals("evt-terminal", failure.eventId());
        assertEquals("default", failure.tenantId());
    }

    @Test
    void admissionBackpressureIsClassifiedAsGloballyRetryable() {
        DetectEngineService engine = mock(DetectEngineService.class);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(new TenantAdmission.RejectedException(
                        "default", TenantAdmission.RejectionReason.PENDING_BYTES)));
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                engine, new InMemoryDetectionStateStore(), null);

        // A budget rejection is not this record's fault: it must be distinguishable
        // from a deterministic failure so the consumer never dead-letters it.
        assertThrows(DetectionRecordProcessor.DetectionUnavailableException.class,
                () -> processor.process("socp-events", 2, 42L, "default|src_ip|198.51.100.10",
                        EVENT_PAYLOAD));
    }
}
