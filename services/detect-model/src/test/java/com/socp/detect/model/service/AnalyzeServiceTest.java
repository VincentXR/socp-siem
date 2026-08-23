package com.socp.detect.model.service;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.store.AnalyzedEntity;
import com.socp.detect.model.store.AnalyzedRepository;
import com.socp.platform.tenant.TenantContext;
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
}
