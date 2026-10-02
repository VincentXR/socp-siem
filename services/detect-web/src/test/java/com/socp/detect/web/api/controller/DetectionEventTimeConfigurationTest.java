package com.socp.detect.web.api.controller;

import com.socp.detect.web.api.request.DetectionIngestRequest;
import com.socp.detect.web.engine.AlertStreamHub;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DetectionEventTimeConfigurationTest {

    @ParameterizedTest
    @CsvSource({"true,2d", "false,0s"})
    void operatorCanExtendOrDisableIngressLimitWithoutRewritingSourceTime(boolean enabled, String allowance) {
        var engine = mock(DetectEngineService.class);
        when(engine.ingest(any())).thenReturn(true);
        when(engine.stats()).thenReturn(Map.of());
        var controller = new DetectionRuntimeController(engine, mock(AlertStreamHub.class),
                mock(Validator.class), enabled, allowance);
        Instant future = Instant.now().plusSeconds(86_400);
        var request = new DetectionIngestRequest("future", future.toString(), "auth", "host",
                "INFO", "login", null, Map.of("ingested_at", future.toString()));
        try (var ignored = TenantContext.open("tenant-time")) {
            assertThat(controller.ingest(request).accepted()).isTrue();
        }
        verify(engine).ingest(argThat(event -> event.id().equals("future")
                && event.timestamp().equals(future) && event.tenantId().equals("tenant-time")
                && !event.fields().get("ingested_at").equals(future.toString())
                && event.fields().get("user_payload.ingested_at").equals(future.toString())));
    }

    @Test
    void invalidIngressConfigurationFailsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new DetectionRuntimeController(
                mock(DetectEngineService.class), mock(AlertStreamHub.class), mock(Validator.class), true, "-1s"));
    }

    @Test
    void generatedTimestampUsesTheSameTrustedReceiptInstant() {
        Instant receivedAt = Instant.parse("2026-10-02T12:00:00Z");
        var request = new DetectionIngestRequest("generated", null, "auth", "host",
                "INFO", "login", null, Map.of());
        var event = request.toSecurityEvent("tenant-time", null, receivedAt);
        assertThat(event.timestamp()).isEqualTo(receivedAt);
        assertThat(event.fields()).containsEntry("ingested_at", receivedAt.toString());
    }
}
