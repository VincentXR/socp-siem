package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectEngineServiceTest {

    @Mock
    private RuleSpecStore store;

    @Mock
    private AlertForwarder forwarder;

    @Mock
    private RuleChangePublisher rulePublisher;

    @Mock
    private DetectionStateStore stateStore;

    @Test
    void addingRuleReloadsEngineAndEvaluatesNewEvents() throws Exception {
        List<Map<String, Object>> persisted = new ArrayList<>();
        when(store.list("default")).thenAnswer(invocation -> List.copyOf(persisted));
        when(store.save(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation -> {
            Map<String, Object> spec = new LinkedHashMap<>(invocation.getArgument(0));
            persisted.add(spec);
            return spec;
        });

        RecentAlertSink sink = new RecentAlertSink(10, null, null);
        DetectEngineService service = new DetectEngineService(store, sink, forwarder, rulePublisher);
        try {
            Map<String, Object> rule = patternRule();
            service.addRule(rule);

            SecurityEvent event = new SecurityEvent(
                    Instant.now(), "auth", "host-1", "Failed password", Map.of(), Severity.HIGH);
            assertTrue(service.ingest(event));

            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (sink.recent().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }

            assertEquals(1, sink.recent().size());
            assertEquals("TEST-PATTERN", sink.recent().get(0).ruleId());
            verify(rulePublisher).publish("TEST-PATTERN", "add");
        } finally {
            service.stop();
        }
    }

    @Test
    void deletingUnknownRuleDoesNotReloadEngine() {
        when(store.delete("missing")).thenReturn(false);

        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, null, null), forwarder, rulePublisher);
        try {
            assertTrue(!service.deleteRule("missing"));
            verify(rulePublisher, org.mockito.Mockito.never()).publish("missing", "delete");
        } finally {
            service.stop();
        }
    }

    @Test
    void partitionRestoreReadsOnlyAssignedState() {
        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, null, null), forwarder, rulePublisher, stateStore);
        try {
            service.restoreForPartitions(Set.of(2));

            assertEquals(Set.of(2), service.assignedPartitions());
            verify(stateStore).replayRecentForPartitions(
                    org.mockito.ArgumentMatchers.eq(Set.of(2)),
                    org.mockito.ArgumentMatchers.any(Duration.class),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            service.stop();
        }
    }

    @Test
    void rebuildDrainsAcceptedWorkBeforeReadingTheJournalSnapshot() throws Exception {
        when(store.list("default")).thenReturn(List.of());
        when(stateStore.claim(org.mockito.ArgumentMatchers.any(SecurityEvent.class)))
                .thenReturn(com.socp.detect.web.persistence.store.DetectionEventClaim.NEW);
        CountDownLatch sinkEntered = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            sinkEntered.countDown();
            assertTrue(releaseSink.await(3, TimeUnit.SECONDS));
            return null;
        }).when(forwarder).forwardAll(
                org.mockito.ArgumentMatchers.any(SecurityEvent.class),
                org.mockito.ArgumentMatchers.anyList());

        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, forwarder, null),
                forwarder, rulePublisher, stateStore);
        service.start();
        try {
            assertTrue(service.ingest(new SecurityEvent(
                    Instant.now(), "system", "host-1", "heartbeat", Map.of(), Severity.INFO)));
            assertTrue(sinkEntered.await(2, TimeUnit.SECONDS));

            CompletableFuture<Void> rebuild = CompletableFuture.runAsync(
                    () -> service.rebuildForPartitions(Set.of(1)));
            Thread.sleep(100);
            verify(stateStore, org.mockito.Mockito.never()).replayRecentForPartitions(
                    org.mockito.ArgumentMatchers.anySet(),
                    org.mockito.ArgumentMatchers.any(Duration.class),
                    org.mockito.ArgumentMatchers.any());

            releaseSink.countDown();
            rebuild.get(3, TimeUnit.SECONDS);
            verify(stateStore).markCompleted(org.mockito.ArgumentMatchers.any(SecurityEvent.class));
            verify(stateStore).replayRecentForPartitions(
                    org.mockito.ArgumentMatchers.eq(Set.of(1)),
                    org.mockito.ArgumentMatchers.any(Duration.class),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            releaseSink.countDown();
            service.stop();
        }
    }

    @Test
    void boundsTenantEnginesAndRestoresAnEvictedTenant() throws Exception {
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, null, null), forwarder, rulePublisher, stateStore);
        ReflectionTestUtils.setField(service, "maxTenantEngines", 1);
        try {
            service.ingestFromKafkaAndAwait(event("tenant-a", "event-a")).join();
            Thread.sleep(5);
            service.ingestFromKafkaAndAwait(event("tenant-b", "event-b")).join();
            assertEquals(2, service.cachedTenantEngines());

            service.evictIdleEngines();
            assertEquals(1, service.cachedTenantEngines());

            service.ingestFromKafkaAndAwait(event("tenant-a", "event-a-2")).join();
            verify(stateStore, org.mockito.Mockito.atLeast(2)).replayRecentForTenant(
                    org.mockito.ArgumentMatchers.eq("tenant-a"),
                    org.mockito.ArgumentMatchers.any(Duration.class),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            service.stop();
        }
    }

    private static SecurityEvent event(String tenant, String id) {
        return new SecurityEvent(id, Instant.now(), "auth", "host-1", "event",
                Map.of("tenant_id", tenant), Severity.INFO);
    }

    private static Map<String, Object> patternRule() {
        return Map.of(
                "id", "TEST-PATTERN",
                "name", "Test auth pattern",
                "type", "pattern",
                "severity", "HIGH",
                "message", "test alert",
                "match", List.of(Map.of("field", "source", "op", "eq", "value", "auth")));
    }
}
