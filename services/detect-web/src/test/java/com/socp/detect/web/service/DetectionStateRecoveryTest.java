package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionStateRecoveryTest {
    @Mock DetectionStateStore journal;
    @Mock DetectionStateSnapshotStore snapshots;
    @Mock DetectionStateOwnership ownership;
    @Mock RuleEngine engine;

    @Test
    void workerCheckpointInstallsEventTenantBeforePersistenceAndClearsItAfterward() throws Exception {
        var state = state(DetectionStateOwnership.noop());
        ReflectionTestUtils.setField(state, "snapshotEveryEvents", 1L);
        when(snapshots.supportsAtomicBatch()).thenReturn(true);
        when(engine.captureStateSnapshot(any())).thenAnswer(invocation -> {
            Function<Map<String, RuleEngine.RuleState>, List<DetectionStateSnapshot>> capture = invocation.getArgument(0);
            return capture.apply(Map.of("rule-a", new RuleEngine.RuleState("rule-a", "v1", new byte[] {1})));
        });
        AtomicReference<String> persistedTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            persistedTenant.set(TenantContext.require());
            assertFalse(TenantContext.isSystemScope());
            return null;
        }).when(snapshots).saveAll(any());

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            workers.submit(() -> {
                assertNull(TenantContext.get());
                state.snapshotAfterDurable(engine, event("tenant-a", "alice"), null, -1L);
                assertNull(TenantContext.get());
                assertFalse(TenantContext.isSystemScope());
            }).get(10, TimeUnit.SECONDS);
        }

        assertEquals("tenant-a", persistedTenant.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-b", "system"})
    void failedCheckpointRestoresTheCallersTenantOrSystemScope(String previous) {
        var state = state(DetectionStateOwnership.noop());
        ReflectionTestUtils.setField(state, "snapshotEveryEvents", 1L);
        when(snapshots.supportsAtomicBatch()).thenReturn(true);
        when(engine.captureStateSnapshot(any())).thenAnswer(invocation -> {
            Function<Map<String, RuleEngine.RuleState>, List<DetectionStateSnapshot>> capture = invocation.getArgument(0);
            return capture.apply(Map.of("rule-a", new RuleEngine.RuleState("rule-a", "v1", new byte[] {1})));
        });
        AtomicReference<String> attemptedTenant = new AtomicReference<>();
        doAnswer(invocation -> {
            attemptedTenant.set(TenantContext.require());
            assertFalse(TenantContext.isSystemScope());
            throw new IllegalStateException("fixture snapshot write unavailable");
        }).when(snapshots).saveAll(any());

        try (var ignored = previous.equals("system") ? TenantContext.openSystem() : TenantContext.open(previous)) {
            state.snapshotAfterDurable(engine, event("tenant-a", "alice"), null, -1L);
            assertEquals(previous.equals("system") ? null : previous, TenantContext.get());
            assertEquals(previous.equals("system"), TenantContext.isSystemScope());
        }
        assertEquals("tenant-a", attemptedTenant.get());
    }

    @Test
    void leasesBelongToKafkaPartitionsAndShardsAndOnlyUnassignedUnitsAreReleased() {
        var state = state(ownership);
        ReflectionTestUtils.setField(state, "stateShardCount", 2);
        ReflectionTestUtils.setField(state, "inputTopic", "routed-events");
        when(ownership.acquire(anyString(), anyInt(), anyInt())).thenAnswer(invocation ->
                new DetectionStateOwnership.Lease(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), "worker-a", 17L, Instant.MAX));

        state.assign(Set.of(2, 4));
        state.acquireStateLeases(Set.of(2, 4));
        state.assign(Set.of(4));
        state.releaseUnassignedStateLeases(Set.of(4));

        verify(ownership, times(4)).acquire(eq("routed-events"), anyInt(), anyInt());
        verify(ownership, times(2)).release(org.mockito.ArgumentMatchers.argThat(lease -> lease.partition() == 2));
        verify(ownership, never()).release(org.mockito.ArgumentMatchers.argThat(lease -> lease.partition() == 4));
        state.releaseAllStateLeases();
        verify(ownership, times(2)).release(org.mockito.ArgumentMatchers.argThat(lease -> lease.partition() == 4));
    }

    @Test
    void revocationBlocksReacquisitionFinalizationAndCheckpointWrites() {
        var state = state(ownership);
        ReflectionTestUtils.setField(state, "snapshotEveryEvents", 1L);
        var lease = new DetectionStateOwnership.Lease("socp-events", 2, 0, "worker-a", 17L, Instant.MAX);
        when(ownership.acquire("socp-events", 2, 0)).thenReturn(lease);
        state.assign(Set.of(2));
        state.durableGuardFor(2, 0).run();

        state.releaseForPartitions(Set.of(2));

        assertEquals(Set.of(), state.assignedPartitions());
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class, () -> state.durableGuardFor(2, 0));
        assertThrows(DetectionStateOwnership.StaleStateOwnerException.class,
                () -> state.assertCurrentOwner(event("tenant-a", "alice"), 2));
        state.snapshotAfterDurable(engine, event("tenant-a", "alice"), 2, 9L);
        verify(ownership).acquire("socp-events", 2, 0);
        verify(ownership).release(lease);
        verifyNoInteractions(engine, snapshots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timestamp", "offsets", "epochs", "topic"})
    void mixedCheckpointGenerationsFallBackToTheFullDurableWindow(String mismatch) {
        var state = state(DetectionStateOwnership.noop());
        Instant timestamp = Instant.parse("2026-10-11T00:00:00Z");
        when(journal.supportsCheckpointReplay()).thenReturn(true);
        when(engine.statefulRuleIds()).thenReturn(List.of("rule-a", "rule-b"));
        when(snapshots.latest("tenant-a", "rule-a", 0)).thenReturn(Optional.of(
                snapshot("rule-a", timestamp, Map.of(2, 9L), Map.of(2, 17L), "socp-events")));
        when(snapshots.latest("tenant-a", "rule-b", 0)).thenReturn(Optional.of(snapshot("rule-b",
                mismatch.equals("timestamp") ? timestamp.plusSeconds(1) : timestamp,
                Map.of(2, mismatch.equals("offsets") ? 10L : 9L),
                Map.of(2, mismatch.equals("epochs") ? 18L : 17L),
                mismatch.equals("topic") ? "another-topic" : "socp-events")));

        state.restoreState("tenant-a", engine, Set.of(), 0);

        verify(engine, never()).restoreStates(anyMap());
        verify(journal).replayRecentForTenant(eq("tenant-a"), eq(Duration.ofMinutes(30)), any());
        verify(journal, never()).replayCompletedAfter(anyString(), any(), any(), any(), any());
    }

    @Test
    void checkpointTailReplayKeepsTenantAndShardBoundaries() {
        var state = state(DetectionStateOwnership.noop());
        ReflectionTestUtils.setField(state, "stateShardCount", 4);
        SecurityEvent owned = event("tenant-a", "alice");
        int shard = state.shardFor(owned);
        SecurityEvent otherShard = event("tenant-a", "bob");
        for (int i = 0; state.shardFor(otherShard) == shard; i++) {
            otherShard = event("tenant-a", "other-" + i);
        }
        List<SecurityEvent> replay = List.of(owned, event("tenant-b", "alice"), otherShard);
        Instant timestamp = Instant.parse("2026-10-11T00:00:00Z");
        when(journal.supportsCheckpointReplay()).thenReturn(true);
        when(engine.statefulRuleIds()).thenReturn(List.of("rule-a"));
        when(engine.restoreStates(anyMap())).thenReturn(List.of("rule-a"));
        when(snapshots.latest("tenant-a", "rule-a", shard)).thenReturn(Optional.of(
                new DetectionStateSnapshot("rule-a", "v1", "tenant-a", shard, 9L, new byte[0], timestamp,
                        Map.of(2, 9L), Map.of(2, 17L), "socp-events")));
        doAnswer(invocation -> {
            Consumer<List<SecurityEvent>> callback = invocation.getArgument(4);
            callback.accept(replay);
            return null;
        }).when(journal).replayCompletedAfter(eq("tenant-a"), eq(timestamp), eq(Set.of(2)), eq(Map.of(2, 9L)), any());

        state.restoreState("tenant-a", engine, Set.of(2), shard);

        verify(engine).restore(List.of(owned));
        verify(journal, never()).replayRecentForPartitions(any(), any(), any());
        verify(journal, never()).replayRecentForTenant(anyString(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void checkpointGenerationKeepsDurableVectorsAndOwnershipOnTheProcessingEngine() {
        var state = state(ownership);
        ReflectionTestUtils.setField(state, "snapshotEveryEvents", 1L);
        when(ownership.acquire(anyString(), anyInt(), anyInt())).thenAnswer(invocation ->
                new DetectionStateOwnership.Lease(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), "worker-a", 17L, Instant.MAX));
        when(snapshots.supportsAtomicBatch()).thenReturn(true);
        when(engine.captureStateSnapshot(any())).thenAnswer(invocation -> {
            Function<Map<String, RuleEngine.RuleState>, List<DetectionStateSnapshot>> capture = invocation.getArgument(0);
            return capture.apply(Map.of(
                    "rule-a", new RuleEngine.RuleState("rule-a", "v1", new byte[] {1}),
                    "rule-b", new RuleEngine.RuleState("rule-b", "v1", new byte[] {2})));
        });
        var input = event("tenant-a", "alice");
        state.recordDurablePosition(input, 1, 10L);
        state.recordDurablePosition(input, 1, 8L);

        state.snapshotAfterDurable(engine, input, 2, 20L);

        ArgumentCaptor<List<DetectionStateSnapshot>> captured = ArgumentCaptor.forClass(List.class);
        verify(snapshots).saveAll(captured.capture());
        List<DetectionStateSnapshot> rows = captured.getValue();
        assertEquals(2, rows.size());
        assertEquals(rows.getFirst().snapshotTimestamp(), rows.getLast().snapshotTimestamp());
        assertTrue(rows.stream().allMatch(row -> row.tenantId().equals("tenant-a")
                && row.partitionOffsets().equals(Map.of(1, 10L, 2, 20L))
                && row.partitionOwnerEpochs().equals(Map.of(1, 17L, 2, 17L))
                && row.inputTopic().equals("socp-events")));
        verify(snapshots, never()).save(any());
    }

    private DetectionStateRecovery state(DetectionStateOwnership leases) {
        var result = new DetectionStateRecovery(journal, snapshots, leases);
        ReflectionTestUtils.setField(result, "recoveryWindow", Duration.ofMinutes(30));
        return result;
    }

    private static DetectionStateSnapshot snapshot(String id, Instant timestamp,
            Map<Integer, Long> offsets, Map<Integer, Long> epochs, String topic) {
        return new DetectionStateSnapshot(id, "v1", "tenant-a", 0, 9L, new byte[0], timestamp, offsets, epochs, topic);
    }

    private static SecurityEvent event(String tenant, String user) {
        return new SecurityEvent("event-" + user, Instant.now(), "auth", "host-1", "test",
                Map.of("tenant_id", tenant, "detection_routing_field", "user",
                        "detection_routing_value", user, "user", user), Severity.INFO);
    }
}
