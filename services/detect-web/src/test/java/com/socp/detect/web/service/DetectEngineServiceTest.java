package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import com.socp.rule.state.StateRoutingKey;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

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

    @Mock
    private DetectionStateSnapshotStore snapshotStore;

    @Mock
    private DetectionPerformanceMetrics performanceMetrics;

    @Test
    void startupInitializesDefaultEngineInsideTenantScope() {
        when(store.list("default")).thenAnswer(invocation -> {
            assertEquals("default", TenantContext.require());
            return List.of();
        });

        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, null, null), forwarder, rulePublisher);
        try {
            service.start();
            assertNull(TenantContext.get());
        } finally {
            service.stop();
        }
    }

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
                    Instant.now(), "auth", "host-1", "Failed password",
                    Map.of("tenant_id", "default"), Severity.HIGH);
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
    void restoreAllRunsTheFullStateReplayInsideSystemScope() {
        AtomicBoolean systemScope = new AtomicBoolean();
        doAnswer(invocation -> {
            systemScope.set(TenantContext.isSystemScope());
            return null;
        }).when(stateStore).replayRecent(
                org.mockito.ArgumentMatchers.any(Duration.class),
                org.mockito.ArgumentMatchers.any());

        DetectEngineService service = new DetectEngineService(
                store, new RecentAlertSink(10, null, null), forwarder, rulePublisher, stateStore);
        try {
            TenantContext.set("tenant-a");
            service.restoreAll();

            assertEquals(Set.of(), service.assignedPartitions());
            assertTrue(systemScope.get());
            verify(stateStore).replayRecent(
                    org.mockito.ArgumentMatchers.any(Duration.class),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            service.stop();
            TenantContext.clear();
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
                    Instant.now(), "system", "host-1", "heartbeat",
                    Map.of("tenant_id", "default"), Severity.INFO)));
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

    @Test
    void snapshotCheckpointIsPeriodicAndFailureDoesNotBreakIngest() {
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(thresholdRule()));
        when(stateStore.supportsCheckpointReplay()).thenReturn(false);
        DetectEngineService service = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                forwarder, rulePublisher, stateStore, performanceMetrics, snapshotStore);
        ReflectionTestUtils.setField(service, "snapshotEveryEvents", 2L);
        SecurityEvent event = event("tenant-a", "snapshot-1");
        try {
            service.snapshotAfterDurable(null, 1, 1L);
            service.snapshotAfterDurable(event, 1, 12L);
            verify(snapshotStore, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
            service.snapshotAfterDurable(event, 1, 12L);
            verify(snapshotStore).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
                    "tenant-a".equals(snapshot.tenantId()) && snapshot.lastProcessedOffset() == 12L));

            org.mockito.Mockito.reset(snapshotStore);
            ReflectionTestUtils.setField(service, "snapshotEveryEvents", 0L);
            org.mockito.Mockito.doThrow(new IllegalStateException("snapshot store down"))
                    .when(snapshotStore).save(org.mockito.ArgumentMatchers.any());
            service.snapshotAfterDurable(event, null, null);
        } finally {
            service.stop();
        }
    }

    @Test
    void configuredStateShardsRouteSnapshotsByTheCanonicalRoutingTuple() {
        TenantContext.set("tenant-a");
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(thresholdRule()));
        when(stateStore.supportsCheckpointReplay()).thenReturn(false);
        DetectEngineService service = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                forwarder, rulePublisher, stateStore, performanceMetrics, snapshotStore);
        ReflectionTestUtils.setField(service, "stateShardCount", 4);
        ReflectionTestUtils.setField(service, "snapshotEveryEvents", 1L);
        SecurityEvent routed = new SecurityEvent("routed-1", Instant.now(), "auth", "host-1", "event",
                Map.of("tenant_id", "tenant-a", "detection_routing_field", "user",
                        "detection_routing_value", "alice", "user", "alice"), Severity.INFO);
        int expectedShard = new StateRoutingKey("tenant-a", "user", "alice").shard(4);
        try {
            service.snapshotAfterDurable(routed, 2, 44L);
            verify(snapshotStore).save(org.mockito.ArgumentMatchers.argThat(snapshot ->
                    snapshot.shardId() == expectedShard && "tenant-a".equals(snapshot.tenantId())));
            assertEquals(4, ((Map<?, ?>) service.stats().get("stateSnapshots")).get("shards"));
        } finally {
            service.stop();
            TenantContext.clear();
        }
    }

    @Test
    void restoresFromCompatibleCheckpointBeforeReplayingTail() {
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(thresholdRule()));
        when(stateStore.supportsCheckpointReplay()).thenReturn(true);
        DetectionStateSnapshot snapshot = new DetectionStateSnapshot(
                "THRESHOLD", "threshold-v1", "tenant-a", 0, 8L,
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), Instant.now());
        when(snapshotStore.latest("default", "THRESHOLD", 0)).thenReturn(Optional.of(snapshot));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<List<SecurityEvent>> consumer = invocation.getArgument(3);
            consumer.accept(List.of(event("tenant-a", "tail-a"), event("tenant-b", "tail-b")));
            return null;
        }).when(stateStore).replayCompletedAfter(org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.any(Instant.class), org.mockito.ArgumentMatchers.anySet(),
                org.mockito.ArgumentMatchers.any());

        DetectEngineService service = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                forwarder, rulePublisher, stateStore, performanceMetrics, snapshotStore);
        try {
            service.start();
            verify(snapshotStore).latest("default", "THRESHOLD", 0);
            verify(stateStore).replayCompletedAfter(org.mockito.ArgumentMatchers.eq("default"),
                    org.mockito.ArgumentMatchers.any(Instant.class), org.mockito.ArgumentMatchers.anySet(),
                    org.mockito.ArgumentMatchers.any());
        } finally {
            service.stop();
        }
    }

    @Test
    void updateAndActivatePreserveLifecycleAndPublishChanges() {
        Map<String, Object> active = new LinkedHashMap<>(patternRule());
        active.put("id", "R1");
        active.put("status", "ACTIVE");
        active.put("enabled", true);
        when(store.get("missing")).thenReturn(null);
        when(store.get("R1")).thenReturn(active);
        when(store.save(org.mockito.ArgumentMatchers.anyMap())).thenAnswer(invocation ->
                new LinkedHashMap<>(invocation.getArgument(0)));
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(store.delete("R1")).thenReturn(true);

        DetectEngineService service = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                forwarder, rulePublisher);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.updateRule(Map.of("id", "missing")));
            Map<String, Object> disabled = new LinkedHashMap<>(active);
            disabled.remove("status");
            disabled.put("enabled", false);
            service.updateRule(disabled);
            verify(store).save(org.mockito.ArgumentMatchers.argThat(value ->
                    "DISABLED".equals(value.get("status"))));

            service.activateRule("R1");
            verify(rulePublisher, org.mockito.Mockito.atLeastOnce()).publish("R1", "update");
            org.junit.jupiter.api.Assertions.assertTrue(service.deleteRule("R1"));
            verify(rulePublisher).publish("R1", "delete");
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> service.activateRule("missing"));
        } finally {
            service.stop();
        }
    }

    @Test
    void terminalClaimsShortCircuitAndKafkaPathsUseEngine() {
        when(stateStore.claim(org.mockito.ArgumentMatchers.any(SecurityEvent.class)))
                .thenReturn(com.socp.detect.web.persistence.store.DetectionEventClaim.COMPLETED,
                        com.socp.detect.web.persistence.store.DetectionEventClaim.DEAD_LETTERED,
                        com.socp.detect.web.persistence.store.DetectionEventClaim.NEW);
        when(store.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        DetectEngineService service = new DetectEngineService(store, new RecentAlertSink(10, null, null),
                forwarder, rulePublisher, stateStore);
        try {
            assertTrue(service.ingest(event("tenant-a", "done-1")));
            assertTrue(service.ingest(event("tenant-a", "dead-1")));
            assertTrue(service.ingest(event("tenant-a", "new-1")));
            assertTrue(service.ingestFromKafka(event("tenant-a", "kafka-1")));
            service.ingestFromKafkaAndAwait(event("tenant-a", "kafka-2")).join();
            verify(stateStore).markCompleted(org.mockito.ArgumentMatchers.any(SecurityEvent.class));
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

    private static Map<String, Object> thresholdRule() {
        return Map.of(
                "id", "THRESHOLD",
                "name", "Threshold",
                "type", "threshold",
                "severity", "HIGH",
                "message", "threshold",
                "status", "ACTIVE",
                "enabled", true,
                "keyField", "host",
                "threshold", 5,
                "window", "60s");
    }
}
