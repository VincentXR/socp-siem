package com.socp.report.web.api.controller;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.report.web.domain.ReportSummary;
import com.socp.report.web.domain.ReportTrend;
import com.socp.report.web.persistence.store.ReportObjectStore;
import com.socp.report.web.service.ReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportControllerTest {

    private ReportService service;
    private ReportObjectStore objectStore;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-report");
        service = mock(ReportService.class);
        objectStore = mock(ReportObjectStore.class);
        controller = new ReportController(service, objectStore);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void delegatesDailyAndTrendQueries() {
        ReportSummary daily = new ReportSummary("2026-08-30", 2,
                Map.of("HIGH", 2), List.of());
        ReportTrend trend = new ReportTrend(List.of("08-30"), List.of(2));
        when(service.dailyReport()).thenReturn(daily);
        when(service.trend7d()).thenReturn(trend);

        assertThat(controller.daily()).isSameAs(daily);
        assertThat(controller.trend7d()).isSameAs(trend);
        verify(service).dailyReport();
        verify(service).trend7d();
    }

    @Test
    void archivesBothReportsUnderTheCurrentTenant() {
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-08-30", 1, Map.of("HIGH", 1), List.of()));
        when(service.trend7d()).thenReturn(new ReportTrend(List.of("08-30"), List.of(1)));
        when(objectStore.put(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = controller.archive();

        assertThat(result).containsEntry("archived", true)
                .extractingByKey("dailyKey").asString()
                .startsWith("reports/tenant-report/");
        assertThat(result.get("trendKey")).asString().startsWith("reports/tenant-report/");
        verify(objectStore, times(2)).put(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("application/json"));
    }

    @Test
    void reportsArchiveFailureInsteadOfReturningAFalseSuccess() {
        when(service.dailyReport()).thenThrow(new IllegalStateException("ClickHouse unavailable"));

        Map<String, Object> result = controller.archive();

        assertThat(result).containsEntry("archived", false)
                .containsEntry("error", "ClickHouse unavailable");
    }

    @Test
    void restrictsArchiveListingAndDownloadsToTheCurrentTenant() {
        List<Map<String, Object>> objects = List.of(Map.of("key", "reports/tenant-report/20260830/daily.json"));
        when(objectStore.list("reports/tenant-report/")).thenReturn(objects);
        when(objectStore.presignedGet("reports/tenant-report/20260830/daily.json"))
                .thenReturn("https://minio/presigned");

        Map<String, Object> listed = controller.archived("reports/");
        Map<String, Object> downloaded = controller.download("reports/tenant-report/20260830/daily.json");

        assertThat(listed).containsEntry("count", 1).containsEntry("objects", objects);
        assertThat(downloaded).containsEntry("url", "https://minio/presigned");
        assertThatThrownBy(() -> controller.archived("reports/other/"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.download("reports/other/20260830/daily.json"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.download("reports/tenant-report/../other.json"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
