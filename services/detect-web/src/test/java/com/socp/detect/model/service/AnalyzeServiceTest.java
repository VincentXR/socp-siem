package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.persistence.entity.AnalyzedEntity;
import com.socp.detect.model.persistence.repository.AnalyzedRepository;
import com.socp.detect.model.persistence.store.AnalysisReceiptStore;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class AnalyzeServiceTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void pagesTenantProjectionAndClampsPageSize() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        AnalyzedEntity entity = new AnalyzedEntity("tenant-a", "alert-1", "rule-1", "Rule 1",
                "HIGH", "message", "host-1", Instant.parse("2026-08-20T00:00:00Z"));
        when(repository.findByTenantId(eq("tenant-a"), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return new PageImpl<>(List.of(entity), pageable, 401);
                });
        AnalyzeService service = new AnalyzeService(repository, new AlertWindowAggregator());
        TenantContext.set("tenant-a");

        AnalyzeService.AnalyzedPage page = service.analyzed(1, 999);

        assertEquals(1, page.page());
        assertEquals(200, page.size());
        assertEquals(401, page.total());
        assertEquals(3, page.totalPages());
        assertEquals("alert-1", page.items().getFirst().id());
    }

    @Test
    void aggregatesStatsInTheRepository() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        when(repository.countByTenantId("tenant-a")).thenReturn(12L);
        when(repository.countBySeverity("tenant-a"))
                .thenReturn(List.<Object[]>of(new Object[]{"HIGH", 7L}, new Object[]{"LOW", 5L}));
        AnalyzeService service = new AnalyzeService(repository, new AlertWindowAggregator());
        TenantContext.set("tenant-a");

        var stats = service.stats();

        assertEquals(12L, stats.get("totalAnalyzed"));
        @SuppressWarnings("unchecked")
        var bySeverity = (java.util.Map<String, Long>) stats.get("bySeverity");
        assertEquals(7L, bySeverity.get("HIGH"));
        assertEquals(0L, bySeverity.get("CRITICAL"));
    }

    @Test
    void appliesConfiguredRetentionAndBoundsTenantRuleState() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        when(repository.countBySeverity(any())).thenReturn(List.of());
        AnalyzeService service = new AnalyzeService(repository, new AlertWindowAggregator());
        ReflectionTestUtils.setField(service, "retention", Duration.ofDays(7));
        ReflectionTestUtils.setField(service, "maxRuleStateTenants", 1);
        service.analyze(java.util.Map.of("tenantId", "tenant-a"));
        service.analyze(java.util.Map.of("tenantId", "tenant-b"));

        Instant before = Instant.now().minus(Duration.ofDays(7)).minusSeconds(1);
        service.cleanupExpired();
        service.evictIdleRuleState();
        Instant after = Instant.now().minus(Duration.ofDays(7)).plusSeconds(1);

        assertEquals(1, service.cachedTenantRuleStates());
        var cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteBefore(cutoff.capture());
        assertTrue(cutoff.getValue().isAfter(before));
        assertTrue(cutoff.getValue().isBefore(after));
    }

    @Test
    void redeliveredSourceAlarmIsAStatePreservingNoOp() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        AnalysisReceiptStore receipts = mock(AnalysisReceiptStore.class);
        when(receipts.claim("tenant-a", "alarm-1", "v1")).thenReturn(false);
        when(repository.countByTenantId("tenant-a")).thenReturn(4L);
        AnalyzeService service = new AnalyzeService(repository, new AlertWindowAggregator(), receipts);

        var result = service.analyze(java.util.Map.of(
                "tenantId", "tenant-a", "sourceAlarmId", "alarm-1", "ruleId", "AUTH-PRIVESC",
                "severity", "HIGH", "entity", "host-1", "message", "duplicate"));

        assertEquals(true, result.get("duplicate"));
        assertEquals(0, result.get("analyzedAlerts"));
        verify(receipts).claim("tenant-a", "alarm-1", "v1");
        verify(repository, never()).save(any(AnalyzedEntity.class));
    }

    @Test
    void stormCollapseRecordsItsOwnOutcomeInsteadOfAFalseCompletion() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        AnalysisReceiptStore receipts = mock(AnalysisReceiptStore.class);
        AlertWindowAggregator window = new AlertWindowAggregator();
        AnalyzeService service = new AnalyzeService(repository, window, receipts);
        ReflectionTestUtils.setField(service, "stormSuppressionThreshold", 1L);
        ReflectionTestUtils.setField(service, "instanceId", "worker-1");
        when(receipts.claim(any(), any(), any())).thenReturn(true);
        TenantContext.set("tenant-a");
        try {
            var first = service.analyze(alarm("alarm-1"));
            int derived = ((Number) first.get("analyzedAlerts")).intValue();
            assertTrue(derived > 0, "测试事件必须能派生出告警，否则本用例没有意义");
            assertEquals(false, first.get("stormSuppressed"));
            assertEquals(derived, first.get("persistedAlerts"));
            verify(receipts).complete("tenant-a", "alarm-1", "v1", derived,
                    AnalysisReceiptStore.STATUS_COMPLETED);

            var second = service.analyze(alarm("alarm-2"));
            assertEquals(true, second.get("stormSuppressed"));
            assertEquals(0, second.get("persistedAlerts"));
            assertEquals("worker-1", second.get("instance"));
            verify(receipts).complete("tenant-a", "alarm-2", "v1", 0,
                    AnalysisReceiptStore.STATUS_SUPPRESSED);
            verify(repository, org.mockito.Mockito.times(derived)).save(any(AnalyzedEntity.class));

            // The replica-local window must not count what was thrown away.
            assertEquals(derived, ((Number) window.snapshot("tenant-a").get("total")).longValue());
            assertEquals(AlertWindowAggregator.REPLICA_LOCAL_SCOPE,
                    window.snapshot("tenant-a").get("scope"));
        } finally {
            TenantContext.clear();
        }
    }

    private static java.util.Map<String, Object> alarm(String sourceAlarmId) {
        return java.util.Map.of(
                "tenantId", "tenant-a",
                "sourceAlarmId", sourceAlarmId,
                "ruleId", "WEB-ATTACK",
                "source", "web",
                "entity", "10.0.0.8",
                "message", "SQLi attempt",
                "severity", "HIGH");
    }

    @Test
    void stormCounterCacheIsBoundedDuringScheduledCleanup() {
        AnalyzedRepository repository = mock(AnalyzedRepository.class);
        AnalyzeService service = new AnalyzeService(repository, new AlertWindowAggregator());
        ReflectionTestUtils.setField(service, "maxStormCounterEntries", 1);

        service.analyze(java.util.Map.of("tenantId", "tenant-a", "ruleId", "r-1", "entity", "host-1"));
        service.analyze(java.util.Map.of("tenantId", "tenant-a", "ruleId", "r-2", "entity", "host-2"));
        assertEquals(2, service.cachedStormCounters());

        service.evictIdleRuleState();

        assertEquals(1, service.cachedStormCounters());
    }
}
