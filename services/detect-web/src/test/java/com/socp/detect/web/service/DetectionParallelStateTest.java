package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DetectionParallelStateTest {
    private static Map<String, Object> rule() {
        return Map.of("id", "threshold", "name", "threshold", "type", "threshold", "enabled", true,
                "keyField", "user", "groupBy", "user", "threshold", 3, "window", "60s", "status", "ACTIVE", "severity", "HIGH");
    }

    private static SecurityEvent event(String tenant, String user, String id) {
        return new SecurityEvent(id, Instant.now(), "auth", "host", "test",
                Map.of("tenant_id", tenant, "user", user, "detection_routing_field", "user",
                        "detection_routing_value", user), Severity.INFO);
    }

    @Test
    void slowShardDoesNotBlockAnotherShardAndEachCheckpointKeepsItsOwnOrderedPrefix() throws Exception {
        var rules = mock(RuleSpecStore.class);
        when(rules.list(anyString())).thenReturn(List.of(rule()));
        var forwarder = mock(AlertForwarder.class);
        when(forwarder.ownsJournalTransaction()).thenReturn(true);
        var snapshots = mock(DetectionStateSnapshotStore.class);
        when(snapshots.supportsAtomicBatch()).thenReturn(true);
        var saved = new ConcurrentHashMap<Integer, DetectionStateSnapshot>();
        doAnswer(call -> {
            List<DetectionStateSnapshot> rows = call.getArgument(0);
            rows.forEach(row -> saved.put(row.shardId(), row));
            return null;
        }).when(snapshots).saveAll(anyList());
        var service = new DetectEngineService(rules, new RecentAlertSink(10, forwarder, null),
                new InMemoryDetectionStateStore(), RuleProcessingObserver.NOOP, snapshots,
                DetectionStateOwnership.noop());
        var state = (DetectionStateRecovery) ReflectionTestUtils.getField(service, "state");
        ReflectionTestUtils.setField(state, "stateShardCount", 4);
        ReflectionTestUtils.setField(state, "snapshotEveryEvents", 1L);
        ReflectionTestUtils.setField(state, "snapshotMinIntervalMs", 0L);
        String slowUser = "alice";
        String fastUser = "bob";
        int slowShard = state.shardFor(event("tenant-a", slowUser, "probe"));
        while (state.shardFor(event("tenant-a", fastUser, "probe")) == slowShard) fastUser += "x";
        int fastShard = state.shardFor(event("tenant-a", fastUser, "probe"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slowTransactions = new AtomicInteger();
        var slowAlerts = new AtomicInteger();
        doAnswer(call -> {
            List<com.socp.rule.engine.BatchEventAlertSink.Entry> entries = call.getArgument(0);
            List<DetectionResult> results = entries.stream().map(com.socp.rule.engine.BatchEventAlertSink.Entry::result).toList();
            assertEquals("tenant-a", TenantContext.require());
            if (results.getFirst().event().fields().get("user").equals(slowUser)) {
                if (slowTransactions.incrementAndGet() == 1) {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                slowAlerts.addAndGet(results.stream().mapToInt(result -> result.alerts().size()).sum());
            }
            return null;
        }).when(forwarder).forwardBatch(anyList());
        try {
            var prefix = List.of(
                    service.prepareBatchEvent(event("tenant-a", slowUser, "a1"), "events", 0, 10),
                    service.prepareBatchEvent(event("tenant-a", fastUser, "b1"), "events", 0, 11),
                    service.prepareBatchEvent(event("tenant-a", slowUser, "a2"), "events", 0, 12),
                    service.prepareBatchEvent(event("tenant-a", fastUser, "b2"), "events", 0, 13));
            var groups = service.batchGroups(prefix);
            assertEquals(2, groups.size());
            assertEquals(List.of(10L, 12L), groups.getFirst().stream().map(e -> e.position().offset()).toList());
            var slow = service.ingestBatchFromKafkaAndAwait(groups.getFirst());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var nextSlow = service.ingestBatchFromKafkaAndAwait(List.of(
                    service.prepareBatchEvent(event("tenant-a", slowUser, "a3"), "events", 0, 14)));
            service.ingestBatchFromKafkaAndAwait(groups.getLast()).get(3, TimeUnit.SECONDS);
            assertFalse(slow.isDone());
            assertFalse(nextSlow.isDone());
            assertEquals(1, slowTransactions.get());
            assertEquals(Set.of(fastShard), saved.keySet());
            assertEquals(Map.of(0, 13L), saved.get(fastShard).partitionOffsets());
            release.countDown();
            slow.get(3, TimeUnit.SECONDS);
            nextSlow.get(3, TimeUnit.SECONDS);
            assertEquals(2, slowTransactions.get());
            assertEquals(1, slowAlerts.get(), "the third same-entity event must see the committed first two");
            assertEquals(Map.of(0, 14L), saved.get(slowShard).partitionOffsets());
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,4,false", "4,1,false", "4,8,false", "4,4,true"})
    void stateLayoutChangesCannotReuseAnIncompatibleCheckpoint(int sourceShards, int targetShards, boolean compatible) {
        var rules = mock(RuleSpecStore.class);
        when(rules.list(anyString())).thenReturn(List.of(rule()));
        var sink = new RecentAlertSink(10, null, null);
        var sourceFactory = new DetectionEngineFactory(rules, sink, null, RuleProcessingObserver.NOOP, () -> sourceShards);
        var targetFactory = new DetectionEngineFactory(rules, sink, null, RuleProcessingObserver.NOOP, () -> targetShards);
        try (RuleEngine source = sourceFactory.build("source", "tenant-a", List.of(), null);
             RuleEngine target = targetFactory.build("target", "tenant-a", List.of(), null)) {
            source.restore(List.of(event("tenant-a", "alice", "a1"), event("tenant-a", "alice", "a2")));
            Map<String, RuleEngine.RuleState> checkpoint = source.captureStateSnapshot(Map::copyOf);
            assertTrue(checkpoint.values().stream().allMatch(row -> row.version().length() <= 64));
            assertEquals(compatible ? List.of("threshold") : List.of(), target.restoreStates(checkpoint));
        }
    }

    @Test
    void unversionedLayoutCannotBeMistakenForASingleShardCheckpoint() {
        var rules = mock(RuleSpecStore.class);
        when(rules.list(anyString())).thenReturn(List.of(rule()));
        var factory = new DetectionEngineFactory(rules, new RecentAlertSink(10, null, null), null,
                RuleProcessingObserver.NOOP, () -> 1);
        var spec = new com.socp.rule.config.RuleSpec(rule());
        var legacy = (com.socp.rule.state.StatefulRule) spec.toRule();
        legacy.accept(event("tenant-a", "alice", "legacy"));
        var checkpoint = new RuleEngine.RuleState("threshold",
                legacy.stateVersion() + ":" + spec.stateSemanticsFingerprint(), legacy.snapshotState());
        try (var target = factory.build("target", "tenant-a", List.of(), null)) {
            assertEquals(List.of(), target.restoreStates(Map.of("threshold", checkpoint)));
        }
    }
}
