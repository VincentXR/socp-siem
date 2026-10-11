package com.socp.detect.web.service;

import com.socp.detect.web.engine.AlertForwarder;
import com.socp.detect.web.engine.RecentAlertSink;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionEngineLifecycleTest {
    @Mock RuleSpecStore store;
    @Mock DetectionStateStore journal;

    @Test
    void slowTenantRebuildOnlyClosesThatTenantsAdmissionScope() throws Exception {
        CountDownLatch compiling = new CountDownLatch(1);
        CountDownLatch releaseCompile = new CountDownLatch(1);
        AtomicBoolean block = new AtomicBoolean();
        when(store.tenant()).thenAnswer(ignored -> TenantContext.require());
        when(store.list(anyString())).thenAnswer(invocation -> {
            if ("tenant-a".equals(invocation.getArgument(0)) && block.get()) {
                compiling.countDown();
                assertTrue(releaseCompile.await(3, TimeUnit.SECONDS));
            }
            return List.of();
        });
        var state = new DetectionStateRecovery(journal, null, DetectionStateOwnership.noop());
        var sink = new RecentAlertSink(10, null, null);
        var lifecycle = new DetectionEngineLifecycle(store, sink, RuleProcessingObserver.NOOP, state);
        var runtime = new DetectEngineService(store, sink, journal, lifecycle, state);
        CompletableFuture<Void> reload = null;
        try {
            runtime.ingestFromKafkaAndAwait(event("tenant-a", "first-a")).join();
            runtime.ingestFromKafkaAndAwait(event("tenant-b", "first-b")).join();
            block.set(true);
            reload = CompletableFuture.runAsync(() -> TenantContext.runWith("tenant-a", runtime::reload));
            assertTrue(compiling.await(2, TimeUnit.SECONDS));

            assertFalse(lifecycle.readyFor("tenant-a", 0));
            assertTrue(lifecycle.readyFor("tenant-b", 0));
            var rejected = assertThrows(CompletionException.class,
                    () -> runtime.ingestFromKafkaAndAwait(event("tenant-a", "held-a")).join());
            assertTrue(rejected.getCause() instanceof DetectEngineService.RuntimeUnavailableException);
            runtime.ingestFromKafkaAndAwait(event("tenant-b", "live-b")).get(2, TimeUnit.SECONDS);

            releaseCompile.countDown();
            reload.get(3, TimeUnit.SECONDS);
            assertEquals(DetectEngineService.RecoveryStatus.READY, runtime.recoveryStatus());
            runtime.ingestFromKafkaAndAwait(event("tenant-a", "recovered-a")).join();
        } finally {
            releaseCompile.countDown();
            if (reload != null) reload.get(3, TimeUnit.SECONDS);
            runtime.stop();
        }
    }

    @Test
    void drainTimeoutRetainsTheLiveEngineAndRetriesAfterItsAcceptedWorkCompletes() throws Exception {
        when(store.tenant()).thenReturn("default");
        when(store.list("default")).thenReturn(List.of());
        CountDownLatch inSink = new CountDownLatch(1);
        CountDownLatch releaseSink = new CountDownLatch(1);
        var forwarder = mock(AlertForwarder.class);
        doAnswer(invocation -> {
            inSink.countDown();
            assertTrue(releaseSink.await(3, TimeUnit.SECONDS));
            return null;
        }).when(forwarder).forward(any(com.socp.rule.engine.DetectionResult.class), any());
        var sink = new RecentAlertSink(10, forwarder, null);
        var state = new DetectionStateRecovery(journal, null, DetectionStateOwnership.noop());
        var lifecycle = new DetectionEngineLifecycle(store, sink, RuleProcessingObserver.NOOP, state);
        ReflectionTestUtils.setField(lifecycle, "reloadDrainTimeoutMs", 10L);
        var runtime = new DetectEngineService(store, sink, journal, lifecycle, state);
        try {
            var live = lifecycle.engineFor("default", 0);
            var completion = runtime.ingestFromKafkaAndAwait(event("default", "accepted"));
            assertTrue(inSink.await(2, TimeUnit.SECONDS));

            runtime.reload();

            assertSame(live, lifecycle.engineFor("default", 0));
            assertTrue(lifecycle.readyFor("default", 0));
            assertTrue(lifecycle.recoveryStats("default").containsKey("pendingRebuilds"));
            assertTrue(runtime.ingestFromKafka(event("default", "still-serving")));
            releaseSink.countDown();
            completion.get(3, TimeUnit.SECONDS);
            assertTrue(live.awaitIdle(2_000L));
            lifecycle.retryDegradedEngines();

            assertNotSame(live, lifecycle.engineFor("default", 0));
            assertFalse(lifecycle.recoveryStats("default").containsKey("pendingRebuilds"));
        } finally {
            releaseSink.countDown();
            runtime.stop();
        }
    }

    private static SecurityEvent event(String tenant, String id) {
        return new SecurityEvent(id, Instant.now(), "auth", "host-1", "test",
                Map.of("tenant_id", tenant), Severity.INFO);
    }
}
