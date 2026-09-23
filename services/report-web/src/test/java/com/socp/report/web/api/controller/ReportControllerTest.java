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

        assertThat(controller.daily().data()).isSameAs(daily);
        assertThat(controller.trend7d().data()).isSameAs(trend);
        verify(service).dailyReport();
        verify(service).trend7d();
    }

    @Test
    void archivesBothReportsInOneTenantSnapshot() throws Exception {
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-08-30", 1, Map.of("HIGH", 1), List.of()));
        when(service.trend7d()).thenReturn(new ReportTrend(List.of("08-30"), List.of(1)));
        when(objectStore.put(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = controller.archive().data();

        assertThat(result).containsEntry("archived", true)
                .extractingByKey("archiveKey").asString()
                .startsWith("reports/tenant-report/");
        var payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(objectStore).put(anyString(), payload.capture(), org.mockito.ArgumentMatchers.eq("application/json"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.getValue());
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("daily").path("total").asInt()).isEqualTo(1);
        assertThat(json.path("trend7d").path("counts").get(0).asInt()).isEqualTo(1);
        assertThat(json.path("daily").has("generatedAt")).isTrue();
        assertThat(json.path("trend7d").has("source")).isTrue();

    }

    @Test
    void archiveFailureBecomesAnErrorEnvelopeInsteadOfAFalseSuccess() {
        when(service.dailyReport()).thenThrow(new IllegalStateException("ClickHouse unavailable"));

        assertThatThrownBy(controller::archive)
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                .hasFieldOrPropertyWithValue("code", 503)
                .hasMessageNotContaining("ClickHouse unavailable");
    }

    @Test
    void disabledStorageReturns503ForArchiveListAndDownloadButDailyReadsRemainAvailable() throws Exception {
        ReportObjectStore disabled = new ReportObjectStore(
                "http://localhost:9000", "key", "secret", "reports", false);
        controller = new ReportController(service, disabled);
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-09-23", 1, Map.of("INFO", 1), List.of()));
        when(service.trend7d()).thenReturn(new ReportTrend(List.of("09-23"), List.of(1)));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.socp.platform.error.web.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/reports/archive"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(503));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/reports/archive"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/reports/archive/download")
                        .param("key", "reports/tenant-report/20260923/daily.json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/reports/daily"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test
    void failedSecondSourceDoesNotWriteAnyObject() {
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-09-23", 1, Map.of("INFO", 1), List.of()));
        when(service.trend7d()).thenThrow(new IllegalStateException("source unavailable"));
        assertThatThrownBy(controller::archive)
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class)
                .hasFieldOrPropertyWithValue("code", 503);
        org.mockito.Mockito.verifyNoInteractions(objectStore);
    }

    @Test
    void retriesAfterAnAmbiguousWriteKeepPreviousSnapshotsIntact() throws Exception {
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-09-23", 1, Map.of("INFO", 1), List.of()));
        when(service.trend7d()).thenReturn(new ReportTrend(List.of("09-23"), List.of(1)));
        Map<String, String> objects = new java.util.LinkedHashMap<>();
        when(objectStore.put(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            assertThat(objects).doesNotContainKey(key);
            objects.put(key, invocation.getArgument(1));
            if (objects.size() == 2) throw new IllegalStateException("response lost after storage write");
            return key;
        });
        String firstKey = (String) controller.archive().data().get("archiveKey");
        String firstContent = objects.get(firstKey);
        assertThatThrownBy(controller::archive)
                .isInstanceOf(com.socp.platform.error.exception.ApiException.class);
        String retryKey = (String) controller.archive().data().get("archiveKey");
        assertThat(retryKey).isNotEqualTo(firstKey);
        assertThat(objects).hasSize(3).containsEntry(firstKey, firstContent);
        for (String payload : objects.values()) {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
            assertThat(json.path("daily").path("total").asInt()).isEqualTo(1);
            assertThat(json.path("trend7d").path("counts").get(0).asInt()).isEqualTo(1);
        }
    }

    @Test
    void restrictsArchiveListingAndDownloadsToTheCurrentTenant() {
        List<Map<String, Object>> objects = List.of(Map.of("key", "reports/tenant-report/20260830/daily.json"));
        when(objectStore.list("reports/tenant-report/")).thenReturn(objects);
        when(objectStore.presignedGet("reports/tenant-report/20260830/daily.json"))
                .thenReturn("https://minio/presigned");

        Map<String, Object> listed = controller.archived("reports/").data();
        Map<String, Object> downloaded = controller.download("reports/tenant-report/20260830/daily.json").data();

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
