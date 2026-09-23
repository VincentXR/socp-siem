package com.socp.hips.web.service;

import com.socp.hips.web.persistence.store.EndpointHistoryRetentionStore;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EndpointHistoryRetentionWorkerTest {
    private final EndpointHistoryRetentionStore store = mock(EndpointHistoryRetentionStore.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @AfterEach void clear() { TenantContext.clear(); metrics.close(); }

    private EndpointHistoryRetentionWorker worker(boolean enabled, int batches, long budget) {
        return new EndpointHistoryRetentionWorker(store, enabled, 30, 2, batches, budget,
                new StaticListableBeanFactory(Map.of("metrics", metrics)).getBeanProvider(MeterRegistry.class));
    }

    @Test void boundedBatchesRunInSystemScopeAndReportRemainingLag() {
        var worker = worker(true, 2, 10000);
        when(store.prune(any(), eq(2))).thenAnswer(call -> { assertThat(TenantContext.isSystemScope()).isTrue(); return 2; });
        when(store.oldestEligible(any())).thenReturn(Optional.of(Instant.now().minusSeconds(40 * 86400L)));
        TenantContext.runWith("caller", worker::cleanup);
        assertThat(TenantContext.get()).isNull();
        verify(store, times(2)).prune(any(), eq(2));
        assertThat(metrics.get("socp.hips.history.retention.last.deleted").gauge().value()).isEqualTo(4);
        assertThat(metrics.get("socp.hips.history.retention.lag.seconds").gauge().value()).isGreaterThanOrEqualTo(9 * 86400);
        assertThat(metrics.get("socp.hips.history.retention.last.success.epoch.seconds").gauge().value()).isPositive();
    }

    @Test void emptyBatchStopsAndDisabledWorkerDoesNothing() {
        var worker = worker(true, 10, 10000);
        when(store.prune(any(), eq(2))).thenReturn(0);
        when(store.oldestEligible(any())).thenReturn(Optional.empty());
        worker.cleanup();
        verify(store).prune(any(), eq(2));
        assertThat(metrics.get("socp.hips.history.retention.lag.seconds").gauge().value()).isZero();
        clearInvocations(store);
        worker(false, 10, 10000).cleanup();
        verifyNoInteractions(store);
    }

    @Test void elapsedBudgetStopsStartingBatchesAndFailureDoesNotClaimSuccess() {
        var worker = worker(true, 10, 100);
        when(store.prune(any(), eq(2))).thenAnswer(call -> { Thread.sleep(150); return 2; });
        when(store.oldestEligible(any())).thenReturn(Optional.empty());
        worker.cleanup();
        verify(store).prune(any(), eq(2));
        double success = metrics.get("socp.hips.history.retention.last.success.epoch.seconds").gauge().value();
        when(store.prune(any(), eq(2))).thenThrow(new IllegalStateException("fixture database failure"));
        TenantContext.set("caller");
        assertThatThrownBy(worker::cleanup).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.require()).isEqualTo("caller");
        assertThat(metrics.get("socp.hips.history.retention.last.success.epoch.seconds").gauge().value()).isEqualTo(success);
    }

    @Test void rejectsInvalidRetentionPolicyInsteadOfSilentlyClampingIt() {
        for (int[] limits : new int[][]{{0, 1, 1, 100}, {3651, 1, 1, 100}, {30, 0, 1, 100},
                {30, 1001, 1, 100}, {30, 1, 0, 100}, {30, 1, 101, 100}, {30, 1, 1, 99}, {30, 1, 1, 60001}}) {
            assertThatThrownBy(() -> new EndpointHistoryRetentionWorker(store, true, limits[0], limits[1], limits[2], limits[3],
                    new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
