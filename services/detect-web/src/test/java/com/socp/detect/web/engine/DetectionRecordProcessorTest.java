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

    @Test
    void canonicalAndHttpIngressCannotChooseTheirOwnDeliveryClassOrIdentity() {
        var processor = new DetectionRecordProcessor(mock(DetectEngineService.class),
                new InMemoryDetectionStateStore(), null);
        String raw = """
                {"eventId":"source-1","tenantId":"default","source":"auth","fields":{
                  "src_ip":"203.0.113.10","detection_delivery_id":"forged",
                  "detection_delivery_kind":"STATELESS","detection_routing_version":"detection-routing-v2",
                  "routing_field":"user","routing_value":"attacker"}}
                """;
        var record = processor.parse("key", raw);
        assertEquals("default|src_ip|203.0.113.10", record.routingKey());
        org.junit.jupiter.api.Assertions.assertFalse(com.socp.rule.partition.DetectionDelivery.isRouted(record.event()));
        var http = new com.socp.detect.web.api.request.DetectionIngestRequest(
                "source-1", null, "auth", "host", "HIGH", "msg", raw,
                java.util.Map.of("detection_delivery_id", "forged", "detection_delivery_kind", "STATELESS",
                        "detection_routing_version", "detection-routing-v2"));
        var event = http.toSecurityEvent("tenant-a");
        assertEquals("source-1", com.socp.rule.partition.DetectionDelivery.deliveryId(event));
        assertEquals("forged", event.fields().get("user_payload.detection_delivery_id"));
    }

    @Test
    void routedInputRequiresACompleteConsistentEnvelope() throws Exception {
        var processor = new DetectionRecordProcessor(mock(DetectEngineService.class),
                new InMemoryDetectionStateStore(), null);
        processor.setRoutedInput(true);
        assertThrows(DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", EVENT_PAYLOAD));
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("detection_delivery_schema", "detection-delivery-schema-v2");
        fields.put("detection_routing_version", "detection-routing-v2");
        fields.put("detection_route_plan_version", "plan-1");
        fields.put("detection_source_event_id", "source-1");
        fields.put("detection_source_topic", "socp-events");
        fields.put("detection_source_partition", "0");
        fields.put("detection_source_offset", "42");
        fields.put("detection_delivery_kind", "STATELESS");
        fields.put("detection_delivery_dimension", "_stateless");
        fields.put("detection_delivery_value", "source-1");
        fields.put("detection_routing_field", "_stateless");
        fields.put("detection_routing_value", "source-1");
        String id = com.socp.rule.partition.DetectionDelivery.deliveryId("tenant-a", "source-1",
                "detection-routing-v2", com.socp.rule.partition.DetectionDelivery.Kind.STATELESS, "_stateless", "source-1");
        fields.put("detection_delivery_id", id);
        var payload = java.util.Map.of("eventId", "source-1", "tenantId", "tenant-a",
                "timestamp", "2026-01-01T00:00:00Z", "fields", fields);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(id, com.socp.rule.partition.DetectionDelivery.deliveryId(
                processor.parse("key", mapper.writeValueAsString(payload)).event()));
        fields.put("detection_delivery_id", "forged");
        assertThrows(DetectionRecordProcessor.MalformedDetectionRecordException.class,
                () -> processor.parse("key", mapper.writeValueAsString(payload)));
    }

    private static final String EVENT_PAYLOAD = """
            {"eventId":"evt-terminal","tenantId":"default","source":"auth","host":"web-1",\
            "msg":"login failed","fields":{"src_ip":"198.51.100.10"}}
            """;

    @Test
    void actualRouterPayloadsAreAcceptedForEveryExecutionClass() {
        var repository = mock(com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository.class);
        var plans = mock(com.socp.detect.web.routing.DetectionRoutingPlanRegistry.class);
        java.util.Map<String, Object> rule = java.util.Map.of(
                "id", "AUTH-THRESHOLD", "name", "Auth threshold", "type", "threshold",
                "severity", "HIGH", "threshold", 3, "status", "ACTIVE", "version", "1",
                "groupBy", "src_ip", "routingField", "src_ip", "dataSources", java.util.List.of("auth"));
        given(plans.plan("default")).willReturn(com.socp.detect.web.routing.DetectionRoutingPlan.compile(
                java.util.List.of(rule), 8));
        var router = new com.socp.detect.web.routing.DetectionRouteOutboxService(
                repository, plans, "socp-detection-routed-v2");
        java.util.List<com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity> rows =
                new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            Iterable<com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity> saved =
                    invocation.getArgument(0);
            saved.forEach(rows::add);
            return rows;
        }).when(repository).saveAllAndFlush(any());
        var routed = router.route("socp-events", 2, 41L, """
                {"eventId":"evt-terminal","tenantId":"default","timestamp":"2026-01-01T00:00:00Z",
                 "source":"auth","host":"web-1","msg":"login failed","fields":{"src_ip":"198.51.100.10"}}
                """);
        org.junit.jupiter.api.Assertions.assertFalse(routed.terminalFailure());
        assertEquals(2, rows.size(), "one stateless and one src_ip stateful delivery");
        var processor = new DetectionRecordProcessor(mock(DetectEngineService.class),
                new InMemoryDetectionStateStore(), null);
        processor.setRoutedInput(true);
        var kinds = java.util.EnumSet.noneOf(com.socp.rule.partition.DetectionDelivery.Kind.class);
        for (var row : rows) {
            var parsed = processor.parse(row.getRoutingKey(), row.getPayload());
            assertEquals(row.getRoutingKey(), parsed.routingKey());
            assertEquals(row.getDeliveryId(), com.socp.rule.partition.DetectionDelivery.deliveryId(parsed.event()));
            assertEquals("evt-terminal", parsed.event().id());
            kinds.add(com.socp.rule.partition.DetectionDelivery.kind(parsed.event()));
        }
        assertEquals(java.util.EnumSet.allOf(com.socp.rule.partition.DetectionDelivery.Kind.class), kinds);
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void failureClassificationTerminatesForCyclicCauses() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertEquals(DetectionRecordProcessor.FailureCategory.UNKNOWN,
                DetectionRecordProcessor.classifyFailure(first));
        assertEquals(DetectionRecordProcessor.FailureCategory.DEPENDENCY,
                DetectionRecordProcessor.classifyFailure(new IllegalArgumentException("adapter",
                        new com.socp.rule.engine.RuleDependencyException("unavailable", first))));
    }

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
        given(stateStore.claim(any(SecurityEvent.class), any(), any(), any(), anyString()))
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
