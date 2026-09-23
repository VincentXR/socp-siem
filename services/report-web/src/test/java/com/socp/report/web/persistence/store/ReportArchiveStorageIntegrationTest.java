package com.socp.report.web.persistence.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.report.web.api.controller.ReportController;
import com.socp.report.web.domain.ReportSummary;
import com.socp.report.web.domain.ReportTrend;
import com.socp.report.web.service.ReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Runs only against an explicitly provisioned disposable loopback MinIO fixture. */
@EnabledIfEnvironmentVariable(named = "SOCP_REPORT_STORAGE_TEST", matches = "true")
class ReportArchiveStorageIntegrationTest {
    @Test
    void concurrentSnapshotsRoundTripAsCompleteDistinctObjects() throws Exception {
        String endpoint = System.getenv("SOCP_REPORT_STORAGE_TEST_URL");
        assertThat(URI.create(endpoint).getHost()).isEqualTo("127.0.0.1");
        String bucket = "report-test-" + UUID.randomUUID();
        ReportObjectStore store = new ReportObjectStore(endpoint, "report-fixture",
                "report-fixture-secret", bucket, true);
        ReportService service = mock(ReportService.class);
        when(service.dailyReport()).thenReturn(new ReportSummary(
                "2026-09-23", 42, Map.of("INFO", 42), List.of()));
        when(service.trend7d()).thenReturn(new ReportTrend(List.of("09-23"), List.of(42)));
        ReportController controller = new ReportController(service, store);
        Callable<String> archive = () -> {
            TenantContext.set("tenant-fixture");
            try {
                return (String) controller.archive().data().get("archiveKey");
            } finally {
                TenantContext.clear();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2); var http = HttpClient.newHttpClient()) {
            var futures = executor.invokeAll(List.of(archive, archive));
            String first = futures.get(0).get();
            String second = futures.get(1).get();
            assertThat(first).isNotEqualTo(second);
            assertThat(store.list("reports/tenant-fixture/", 10)).hasSize(2);
            assertThat(store.list("reports/other/", 10)).isEmpty();
            for (String key : List.of(first, second)) {
                var response = http.send(HttpRequest.newBuilder(URI.create(store.presignedGet(key))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var json = new ObjectMapper().readTree(response.body());
                assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
                assertThat(json.path("daily").path("total").asInt()).isEqualTo(42);
                assertThat(json.path("trend7d").path("counts").get(0).asInt()).isEqualTo(42);
                assertThat(json.path("daily").has("generatedAt")).isTrue();
            }
        }
    }
}
