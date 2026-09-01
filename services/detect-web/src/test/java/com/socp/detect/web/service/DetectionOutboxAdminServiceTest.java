package com.socp.detect.web.service;

import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.detect.web.persistence.repository.RuleChangeOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DetectionOutboxAdminServiceTest {

    @Mock private DetectionAlertOutboxRepository alertRepository;
    @Mock private RuleChangeOutboxRepository ruleRepository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void requeuePreservesDeliveredStage() {
        TenantContext.set("tenant-a");
        DetectionAlertOutboxEntity event = new DetectionAlertOutboxEntity(
                "alert-1", "tenant-a", "{}", Instant.now());
        event.setStatus("DEAD");
        event.setDeliveredAt(Instant.now());
        given(alertRepository.findByAlertIdAndTenantId("alert-1", "tenant-a"))
                .willReturn(Optional.of(event));
        given(alertRepository.requeueDead(eq("alert-1"), eq("tenant-a"), any(Instant.class)))
                .willReturn(1);

        var result = service().requeueAlert("alert-1");

        assertThat(result.status()).isEqualTo("DELIVERED");
        verify(alertRepository).requeueDead(eq("alert-1"), eq("tenant-a"), any(Instant.class));
    }

    @Test
    void discardsRuleChangeWithForensicFailureContext() {
        TenantContext.set("tenant-a");
        RuleChangeOutbox event = new RuleChangeOutbox();
        event.setId("rule-event-1");
        event.setLastError("Kafka unavailable");
        given(ruleRepository.findByIdAndTenantId("rule-event-1", "tenant-a"))
                .willReturn(Optional.of(event));
        given(ruleRepository.discardDead(eq("rule-event-1"), eq("tenant-a"),
                eq("operator discard: obsolete rule | previous failure: Kafka unavailable"),
                any(Instant.class))).willReturn(1);

        var result = service().discardRuleChange("rule-event-1", "obsolete rule");

        assertThat(result.status()).isEqualTo("DISCARDED");
    }

    private DetectionOutboxAdminService service() {
        return new DetectionOutboxAdminService(alertRepository, ruleRepository,
                new DetectionPerformanceMetrics(new SimpleMeterRegistry()));
    }
}
