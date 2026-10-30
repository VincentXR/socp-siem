package com.socp.detect.web.service;

import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.state.DetectionStateSnapshot;
import com.socp.rule.state.DetectionStateSnapshotStore;
import com.socp.rule.state.StatefulRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class DetectionLazyAssignmentTest {
    private static final Instant TIME = Instant.parse("2026-10-01T00:00:00Z");
    private final RuleSpecStore rules = mock(RuleSpecStore.class);
    private final DetectionStateStore journal = mock(DetectionStateStore.class);
    private final DetectionStateSnapshotStore snapshots = mock(DetectionStateSnapshotStore.class);
    private final RecentAlertSink sink = new RecentAlertSink(10, null, null);

    @Test
    void preAssignmentStatsNeverRestoreHistoricalStateAndFirstEventsKeepTheirCheckpoints() throws Exception {
        when(rules.list(anyString())).thenReturn(List.of(threshold()));
        when(rules.tenant()).thenReturn("tenant-a");
        when(rules.count("tenant-a")).thenReturn(1L);
        when(journal.supportsCheckpointReplay()).thenReturn(true);
        var defaultSnapshot = snapshot("default", 4);
        var historicalSnapshot = snapshot("tenant-a", 4);
        when(snapshots.latest("default", "THRESHOLD", 0)).thenReturn(Optional.of(defaultSnapshot));
        when(snapshots.latest("tenant-a", "THRESHOLD", 0)).thenReturn(Optional.of(historicalSnapshot));
        var service = service(DetectionStateOwnership.noop());
        try {
            service.start();
            assertTrue(service.assignedPartitions().isEmpty());
            assertEquals(1, lifecycle(service).cachedTenantEngines());
            for (int read = 0; read < 3; read++) {
                Map<String, Object> stats = service.stats();
                assertEquals(1L, stats.get("rules"), "configured rule counts do not require a live engine");
                assertTrue(((List<?>) stats.get("ruleStats")).isEmpty());
                assertEquals(1, stats.get("cachedTenantEngines"));
                Map<?, ?> recovery = (Map<?, ?>) stats.get("stateRecovery");
                assertEquals(0L, recovery.get("materializedShards"));
                assertEquals(true, recovery.get("deferredUntilEvent"));
                assertEquals(0L, recovery.get("warmedWithoutHistory"));
            }
            verify(rules, never()).list("tenant-a");
            verify(snapshots, never()).latest("tenant-a", "THRESHOLD", 0);
            verify(journal, never()).replayCompletedAfter(eq("tenant-a"), any(), anySet(), anyMap(), any());

            try (var scope = TenantContext.open("tenant-a")) {
                service.ingestFromKafkaAndAwait(event("tenant-a", "historical-fifth")).get(3, TimeUnit.SECONDS);
            }
            try (var scope = TenantContext.open("default")) {
                service.ingestFromKafkaAndAwait(event("default", "default-fifth")).get(3, TimeUnit.SECONDS);
            }
            assertEquals(2, sink.recent().size(), "both actual first events retain their four checkpoint predecessors");
            assertEquals(2, lifecycle(service).cachedTenantEngines());
            verify(snapshots).latest("tenant-a", "THRESHOLD", 0);
            verify(snapshots).latest("default", "THRESHOLD", 0);
        } finally { service.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-a", "default"})
    void elevenHistoricalTenantsStayColdUntilCheckpointAndTailPrecedeFirstEvent(String tenant) throws Exception {
        discoverHistory();
        when(rules.list(anyString())).thenReturn(List.of(threshold()));
        when(rules.tenant()).thenReturn(tenant);
        when(journal.supportsCheckpointReplay()).thenReturn(true);
        var snapshot = snapshot(tenant, 3);
        when(snapshots.latest(tenant, "THRESHOLD", 0)).thenReturn(Optional.of(snapshot));
        doAnswer(call -> {
            Consumer<List<SecurityEvent>> replay = call.getArgument(4);
            replay.accept(List.of(event(tenant, "tail")));
            return null;
        }).when(journal).replayCompletedAfter(eq(tenant), eq(TIME), eq(Set.of(2)), eq(Map.of(2, 8L)), any());
        var service = service(DetectionStateOwnership.noop());
        try {
            service.rebuildForPartitions(Set.of(2));
            assertTrue(service.isReady());
            assertEquals(0, lifecycle(service).cachedTenantEngines());
            verify(rules, never()).list(anyString());
            verifyNoInteractions(snapshots);
            Map<String, Object> stats = service.stats();
            assertEquals(0, stats.get("cachedTenantEngines"));
            Map<?, ?> recovery = (Map<?, ?>) stats.get("stateRecovery");
            assertEquals("on-first-event", recovery.get("restorationMode"));
            assertEquals(0L, recovery.get("materializedShards"));
            assertEquals(true, recovery.get("deferredUntilEvent"));
            assertEquals(0L, recovery.get("warmedWithoutHistory"));
            assertTrue(sink.recent().isEmpty());

            try (var scope = TenantContext.open(tenant)) {
                service.ingestFromKafkaAndAwait(event(tenant, "new"), "socp-events", 2, 10L)
                        .get(3, TimeUnit.SECONDS);
            }
            assertEquals(1, lifecycle(service).cachedTenantEngines());
            assertEquals(1, sink.recent().size(), "three checkpoint events, one tail, then the new fifth event");
            verify(journal).replayCompletedAfter(eq(tenant), eq(TIME), eq(Set.of(2)), eq(Map.of(2, 8L)), any());
            verify(journal, never()).replayRecentForPartitions(anySet(), any(), any());
        } finally { service.stop(); }
    }

    @Test
    void noRecentTenantRowsStillRestoresDefaultCheckpointInsteadOfClaimingEmptyHistory() throws Exception {
        when(journal.replayRecoveryTenants(anySet(), any(), any())).thenReturn(true);
        when(journal.supportsCheckpointReplay()).thenReturn(true);
        when(rules.list("default")).thenReturn(List.of(threshold()));
        when(rules.tenant()).thenReturn("default");
        when(snapshots.latest("default", "THRESHOLD", 0)).thenReturn(Optional.of(snapshot("default", 4)));
        var service = service(DetectionStateOwnership.noop());
        try {
            service.rebuildForPartitions(Set.of(2));
            assertEquals(1, lifecycle(service).cachedTenantEngines());
            assertEquals(0L, ((Map<?, ?>) service.stats().get("stateRecovery")).get("warmedWithoutHistory"));
            try (var scope = TenantContext.open("default")) {
                service.ingestFromKafkaAndAwait(event("default", "fifth"), "socp-events", 2, 9L)
                        .get(3, TimeUnit.SECONDS);
            }
            assertEquals(1, sink.recent().size());
        } finally { service.stop(); }
    }

    @Test
    void absentCheckpointAndReplayHistoryWarmsOnlyTheEmptyDefault() {
        when(journal.replayRecoveryTenants(anySet(), any(), any())).thenReturn(true);
        when(rules.list("default")).thenReturn(List.of());
        when(rules.tenant()).thenReturn("default");
        var service = service(DetectionStateOwnership.noop());
        try {
            service.rebuildForPartitions(Set.of(2));
            assertEquals(1, lifecycle(service).cachedTenantEngines());
            assertEquals(1L, ((Map<?, ?>) service.stats().get("stateRecovery")).get("warmedWithoutHistory"));
            verify(journal).replayRecentForPartitions(eq(Set.of(2)), any(), any());
        } finally { service.stop(); }
    }

    @Test
    void concurrentColdTenantsRestoreSerially() throws Exception {
        discoverHistory();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var concurrent = new AtomicInteger();
        var maximum = new AtomicInteger();
        doAnswer(call -> {
            int active = concurrent.incrementAndGet();
            maximum.accumulateAndGet(active, Math::max);
            try {
                if ("tenant-a".equals(TenantContext.require())) {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                } else secondEntered.countDown();
            } finally { concurrent.decrementAndGet(); }
            return null;
        }).when(journal).replayRecentForPartitions(anySet(), any(), any());
        var service = service(DetectionStateOwnership.noop());
        try {
            service.rebuildForPartitions(Set.of(2));
            var first = CompletableFuture.runAsync(() -> TenantContext.runWith("tenant-a",
                    () -> lifecycle(service).engineFor("tenant-a", 0)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = CompletableFuture.runAsync(() -> TenantContext.runWith("tenant-b",
                    () -> lifecycle(service).engineFor("tenant-b", 0)));
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            assertEquals(1, maximum.get());
            assertEquals(2, lifecycle(service).cachedTenantEngines());
        } finally { release.countDown(); service.stop(); }
    }

    @Test
    void revokedColdRestoreCannotPublishEvaluateOrMarkTheTenantDegraded() throws Exception {
        discoverHistory();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return null;
        }).when(journal).replayRecentForPartitions(anySet(), any(), any());
        var service = service(DetectionStateOwnership.noop());
        try {
            service.rebuildForPartitions(Set.of(2));
            var admission = CompletableFuture.runAsync(() -> TenantContext.runWith("tenant-a", () ->
                    service.ingestFromKafkaAndAwait(event("tenant-a", "revoked"), "socp-events", 2, 1L).join()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            service.releaseForPartitions(Set.of(2));
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> admission.get(3, TimeUnit.SECONDS));
            assertEquals(0, lifecycle(service).cachedTenantEngines());
            assertEquals(DetectEngineService.RecoveryStatus.READY, service.recoveryStatus());
            assertTrue(sink.recent().isEmpty());
            verify(journal, never()).markCompleted(any(SecurityEvent.class));
        } finally { release.countDown(); service.stop(); }
    }

    @Test
    void degradedAssignmentRetryReacquiresFencingBeforeReadiness() {
        discoverHistory();
        var ownership = mock(DetectionStateOwnership.class);
        var lease = new DetectionStateOwnership.Lease("socp-events", 2, 0, "owner", 1, Instant.MAX);
        when(ownership.acquire("socp-events", 2, 0))
                .thenThrow(new IllegalStateException("ownership unavailable")).thenReturn(lease);
        var service = service(ownership);
        try {
            service.rebuildForPartitions(Set.of(2));
            assertFalse(service.isReady());
            verify(journal, never()).replayRecoveryTenants(anySet(), any(), any());
            lifecycle(service).retryDegradedEngines();
            assertTrue(service.isReady());
            verify(ownership, times(2)).acquire("socp-events", 2, 0);
            verify(ownership).assertCurrent(lease);
            assertEquals(0, lifecycle(service).cachedTenantEngines());
        } finally { service.stop(); }
    }

    private void discoverHistory() {
        doAnswer(call -> {
            assertTrue(TenantContext.isSystemScope());
            Consumer<String> tenant = call.getArgument(2);
            tenant.accept("default");
            tenant.accept("tenant-a");
            for (int i = 0; i < 9; i++) tenant.accept("historical-" + i);
            return true;
        }).when(journal).replayRecoveryTenants(anySet(), any(), any());
    }

    private DetectEngineService service(DetectionStateOwnership ownership) {
        return new DetectEngineService(rules, sink, journal, RuleProcessingObserver.NOOP, snapshots, ownership);
    }

    private static DetectionEngineLifecycle lifecycle(DetectEngineService service) {
        return (DetectionEngineLifecycle) ReflectionTestUtils.getField(service, "lifecycle");
    }

    private static SecurityEvent event(String tenant, String id) {
        return new SecurityEvent(id, TIME, "auth", "host", "event", Map.of("tenant_id", tenant), Severity.INFO);
    }

    private static Map<String, Object> threshold() {
        return Map.of("id", "THRESHOLD", "name", "Threshold", "type", "threshold", "status", "ACTIVE",
                "enabled", true, "keyField", "host", "threshold", 5, "window", "60s",
                "severity", "HIGH", "message", "threshold");
    }

    private static DetectionStateSnapshot snapshot(String tenant, int events) {
        var spec = new RuleSpec(threshold());
        var rule = (StatefulRule) spec.toRule();
        for (int i = 0; i < events; i++) rule.accept(event(tenant, "checkpoint-" + i));
        return new DetectionStateSnapshot("THRESHOLD", rule.stateVersion() + ":" + spec.stateSemanticsFingerprint(),
                tenant, 0, 8L, rule.snapshotState(), TIME, Map.of(2, 8L));
    }
}
