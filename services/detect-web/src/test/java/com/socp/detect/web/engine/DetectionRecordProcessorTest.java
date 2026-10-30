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

    private static java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>> records() {
        return java.util.stream.IntStream.range(0,2).mapToObj(n->new org.apache.kafka.clients.consumer.ConsumerRecord<>(
                "socp-events",0,(long)n,"key","{\"eventId\":\"batch-"+n+"\",\"tenantId\":\"default\",\"source\":\"auth\"}")).toList();
    }
    private static void enableBatch(DetectEngineService engine) {
        org.mockito.Mockito.when(engine.prepareBatchEvent(any(),anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(i->new com.socp.rule.engine.RuleEngine.BatchEvent(i.getArgument(0),
                        new com.socp.rule.engine.DetectionResult.InputPosition(i.getArgument(1),i.getArgument(2),i.getArgument(3)),
                        com.socp.rule.engine.RuleExecutionScope.NOOP));
        org.mockito.Mockito.when(engine.batchGroups(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(i -> java.util.List.of(java.util.List.copyOf(i.getArgument(0))));
    }
    @Test void batchRetainsItsOriginalFutureAcrossTimeoutAndOnlyRetriesFinalFence() throws Exception {
        var engine=mock(DetectEngineService.class);var state=mock(DetectionStateStore.class);enableBatch(engine);
        var durable=new CompletableFuture<Void>();var admitted=new java.util.concurrent.CountDownLatch(1);
        given(state.claimBatch(org.mockito.ArgumentMatchers.anyList())).willReturn(java.util.List.of(DetectionEventClaim.NEW,DetectionEventClaim.NEW));
        org.mockito.Mockito.when(engine.ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList())).thenAnswer(i->{admitted.countDown();return durable;});
        org.mockito.Mockito.doThrow(new org.springframework.transaction.CannotCreateTransactionException("transient"))
                .doNothing().when(engine).assertCurrentOwner(any(),org.mockito.ArgumentMatchers.eq(0));
        var processor=new DetectionRecordProcessor(engine,state,null,20L);
        var result=CompletableFuture.supplyAsync(()->processor.processBatch(records()));
        assertTrue(admitted.await(2,java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(50);assertTrue(!result.isDone());durable.complete(null);
        assertTrue(result.get(3,java.util.concurrent.TimeUnit.SECONDS));
        verify(engine,times(1)).ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList());
        verify(state,times(1)).claimBatch(org.mockito.ArgumentMatchers.anyList());
        verify(engine,times(2)).assertCurrentOwner(any(),org.mockito.ArgumentMatchers.eq(0));
        org.mockito.Mockito.verify(state,org.mockito.Mockito.never()).markCompleted(any(SecurityEvent.class));
    }
    @Test void terminalBatchEntriesAreNotEvaluatedAgain() {
        var engine=mock(DetectEngineService.class);var state=mock(DetectionStateStore.class);enableBatch(engine);
        given(state.claimBatch(org.mockito.ArgumentMatchers.anyList())).willReturn(java.util.List.of(DetectionEventClaim.COMPLETED,DetectionEventClaim.NEW));
        org.mockito.Mockito.when(engine.ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList())).thenAnswer(i->{
            java.util.List<com.socp.rule.engine.RuleEngine.BatchEvent> pending=i.getArgument(0);
            assertEquals(1,pending.size());assertEquals("batch-1",pending.getFirst().event().id());
            return CompletableFuture.completedFuture(null);
        });
        assertTrue(new DetectionRecordProcessor(engine,state,null).processBatch(records()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "async-failure", "admission-failure"})
    void parallelGroupsAllSettleBeforePartitionCompletionOrRecordRetry(String outcome) throws Exception {
        var engine = mock(DetectEngineService.class);
        var state = mock(DetectionStateStore.class);
        enableBatch(engine);
        org.mockito.Mockito.when(engine.batchGroups(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            java.util.List<com.socp.rule.engine.RuleEngine.BatchEvent> events = call.getArgument(0);
            return events.stream().map(java.util.List::of).toList();
        });
        given(state.claimBatch(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(java.util.List.of(DetectionEventClaim.NEW, DetectionEventClaim.NEW));
        var slow = new CompletableFuture<Void>();
        var otherSubmitted = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(engine.ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            assertEquals("default", com.socp.platform.tenant.context.TenantContext.require());
            java.util.List<com.socp.rule.engine.RuleEngine.BatchEvent> events = call.getArgument(0);
            if (events.getFirst().position().offset() == 0) return slow;
            otherSubmitted.countDown();
            if (outcome.equals("admission-failure")) throw new IllegalStateException("second group not admitted");
            return outcome.equals("success") ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("second transaction rolled back"));
        });
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> new DetectionRecordProcessor(engine, state, null, 10L).processBatch(records()));
            try {
                assertTrue(otherSubmitted.await(3, java.util.concurrent.TimeUnit.SECONDS),
                        "a slow state unit must not prevent another unit from starting");
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> result.get(50, java.util.concurrent.TimeUnit.MILLISECONDS),
                        "neither ACK nor fallback may escape while any submitted transaction is in flight");
                org.mockito.Mockito.verify(engine, org.mockito.Mockito.never())
                        .assertCurrentOwner(any(), org.mockito.ArgumentMatchers.anyInt());
                slow.complete(null);
                if (outcome.equals("success")) {
                    assertTrue(result.get(3, java.util.concurrent.TimeUnit.SECONDS));
                    verify(engine, times(2)).assertCurrentOwner(any(), org.mockito.ArgumentMatchers.eq(0));
                } else {
                    assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> result.get(3, java.util.concurrent.TimeUnit.SECONDS));
                }
                verify(engine, times(2)).ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList());
            } finally {
                slow.complete(null);
            }
        }
    }

    @Test
    void allTenantClaimsFinishBeforeAnyParallelEvaluationStarts() {
        var engine = mock(DetectEngineService.class);
        var state = mock(DetectionStateStore.class);
        enableBatch(engine);
        org.mockito.Mockito.when(engine.batchGroups(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            java.util.List<com.socp.rule.engine.RuleEngine.BatchEvent> events = call.getArgument(0);
            return events.stream().map(java.util.List::of).toList();
        });
        given(state.claimBatch(org.mockito.ArgumentMatchers.anyList())).willAnswer(call -> {
            java.util.List<com.socp.detect.web.persistence.store.DetectionEventInput> inputs = call.getArgument(0);
            String tenant = inputs.getFirst().event().requireTenantId();
            assertEquals(tenant, com.socp.platform.tenant.context.TenantContext.require());
            if (tenant.equals("second")) throw new IllegalStateException("claim unavailable");
            return java.util.List.of(DetectionEventClaim.NEW);
        });
        var mixed = new java.util.ArrayList<>(records());
        var second = mixed.get(1);
        mixed.set(1, new org.apache.kafka.clients.consumer.ConsumerRecord<>(second.topic(), second.partition(),
                second.offset(), second.key(), second.value().replace("default", "second")));
        assertThrows(DetectionRecordProcessor.RetryableDetectionFailure.class,
                () -> new DetectionRecordProcessor(engine, state, null).processBatch(mixed));
        verify(engine, org.mockito.Mockito.never()).ingestBatchFromKafkaAndAwait(org.mockito.ArgumentMatchers.anyList());
        assertEquals(null, com.socp.platform.tenant.context.TenantContext.get());
    }

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
    void atomicJournalSinkAvoidsSecondFinalizationOnlyAfterSuccessfulCompletion() {
        DetectEngineService engine = mock(DetectEngineService.class);
        DetectionStateStore store = mock(DetectionStateStore.class);
        given(store.claim(any(SecurityEvent.class), any(), any(), any(), anyString()))
                .willReturn(DetectionEventClaim.NEW);
        given(engine.completesJournal(store)).willReturn(true);
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.completedFuture(null));
        var processor = new DetectionRecordProcessor(engine, store, null);
        processor.process("socp-events", 2, 42L, "default|src_ip|198.51.100.10", EVENT_PAYLOAD);
        verify(store, org.mockito.Mockito.never()).markCompleted(any(SecurityEvent.class));
        given(engine.ingestFromKafkaAndAwait(any(SecurityEvent.class), anyString(), any(), any()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("commit failed")));
        assertThrows(DetectionRecordProcessor.RetryableDetectionFailure.class,
                () -> processor.process("socp-events", 2, 43L,
                        "default|src_ip|198.51.100.10", EVENT_PAYLOAD));
        verify(store, org.mockito.Mockito.never()).markCompleted(any(SecurityEvent.class));
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
