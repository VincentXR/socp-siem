package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

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
    void anUnknownDurableResultFailureStaysRetryableWithIdentityAndStage() {
        DetectEngineService engine = mock(DetectEngineService.class);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("durable sink rejected this event")));
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                engine, new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.RetryableDetectionFailure failure = assertThrows(
                DetectionRecordProcessor.RetryableDetectionFailure.class,
                () -> processor.process("socp-events", 2, 42L, "default|src_ip|198.51.100.10",
                        EVENT_PAYLOAD));

        assertEquals("evt-terminal", failure.eventId());
        assertEquals("default", failure.tenantId());
        assertEquals(DetectionRecordProcessor.FailureStage.ASYNC_EXECUTION, failure.stage());
        assertEquals(DetectionRecordProcessor.FailureCategory.UNKNOWN, failure.category());
    }

    @Test
    void timeoutResumesTheOriginalAsyncEvaluationInsteadOfStartingAnotherOne() {
        DetectEngineService engine = mock(DetectEngineService.class);
        DetectionStateStore stateStore = mock(DetectionStateStore.class);
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        CompletableFuture<Void> original = new CompletableFuture<>();
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(original);
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                engine, stateStore, null, 1L);

        DetectionRecordProcessor.InFlightDetectionTimeout timedOut = assertThrows(
                DetectionRecordProcessor.InFlightDetectionTimeout.class,
                () -> processor.process("socp-events", 2, 42L,
                        "default|src_ip|198.51.100.10", EVENT_PAYLOAD));

        original.complete(null);
        processor.resumeTimedOut(timedOut, 100L);

        verify(engine, times(1)).ingestFromKafkaAndAwait(
                any(SecurityEvent.class), anyString(), any(), any());
        verify(stateStore).markCompleted(any(SecurityEvent.class));
    }

    @Test
    void admissionBackpressureIsTypedAndRetryable() {
        DetectEngineService engine = mock(DetectEngineService.class);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(new TenantAdmission.RejectedException(
                        "default", TenantAdmission.RejectionReason.PENDING_BYTES)));
        DetectionRecordProcessor processor = new DetectionRecordProcessor(
                engine, new InMemoryDetectionStateStore(), null);

        DetectionRecordProcessor.RetryableDetectionFailure failure = assertThrows(
                DetectionRecordProcessor.RetryableDetectionFailure.class,
                () -> processor.process("socp-events", 2, 42L, "default|src_ip|198.51.100.10",
                        EVENT_PAYLOAD));

        assertEquals(DetectionRecordProcessor.FailureCategory.BACKPRESSURE, failure.category());
        assertEquals(DetectionRecordProcessor.FailureStage.ASYNC_EXECUTION, failure.stage());
    }
}
