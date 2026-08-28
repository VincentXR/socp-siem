package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlarmStatisticsServiceTest {

    @Mock
    private AlarmRepository repository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void usesDatabaseAggregationsInsteadOfLoadingEveryTenantAlarm() {
        TenantContext.set("tenant-a");
        given(repository.countForStatistics(eq("tenant-a"), any())).willReturn(3L);
        given(repository.countBySeverityForStatistics(eq("tenant-a"), any()))
                .willReturn(List.of(new AlarmSeverityCount(Severity.HIGH, 3)));
        given(repository.countByRiskLevelForStatistics(eq("tenant-a"), any()))
                .willReturn(List.of(new AlarmRiskLevelCount("HIGH", 2)));
        given(repository.topRulesForStatistics(eq("tenant-a"), any(), any()))
                .willReturn(List.of(new AlarmRuleCount("R-1", 3)));
        given(repository.averageRiskForStatistics(eq("tenant-a"), any())).willReturn(66.64);
        given(repository.topRiskForStatistics(eq("tenant-a"), any(), any())).willReturn(List.of());
        given(repository.countByTenantIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(
                eq("tenant-a"), any(), any())).willReturn(0L);

        Map<String, Object> stats = new AlarmStatisticsService(repository).stats("7d");

        assertThat(stats).containsEntry("total", 3L).containsEntry("avgRisk", 66.6);
        assertThat(((Map<?, ?>) stats.get("bySeverity")).get("HIGH")).isEqualTo(3L);
        assertThat(((Map<?, ?>) stats.get("byRiskLevel")).get("HIGH")).isEqualTo(2L);
        assertThat(((List<?>) stats.get("topRules")).getFirst())
                .isEqualTo(Map.of("ruleId", "R-1", "count", 3L));
        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repository).countForStatistics(eq("tenant-a"), since.capture());
        assertThat(since.getValue()).isNotNull();
        verify(repository, never()).findByTenantId(any());
    }

    @Test
    void allWindowUsesTypedEpochBoundaryInsteadOfNullableDatabaseParameter() {
        assertThat(AlarmStatisticsService.windowStart("all")).isEqualTo(Instant.EPOCH);
        assertThat(AlarmStatisticsService.windowStart("unknown")).isEqualTo(Instant.EPOCH);
    }
}
