package com.socp.alert.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AlertPerformanceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void recordsRequestToCommitLatencyAndDatabaseTransaction() {
        AlertPerformanceMetrics metrics = new AlertPerformanceMetrics(registry);
        AlertPerformanceMetrics.Sample sample = metrics.requestReceived(Instant.now().minusSeconds(1));

        metrics.committed(sample, Instant.now().minusSeconds(1));
        metrics.committed(null, null);

        assertThat(registry.find("socp.alert.stage").tag("stage", "transport_request")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("socp.alert.stage").tag("stage", "alert_persistence")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("socp.alert.stage").tag("stage", "durable_alert")
                .timer().count()).isEqualTo(1);
        assertThat(registry.find("socp.alert.db.transactions")
                .tag("operation", "create").counter().count()).isEqualTo(1);
    }

    @Test
    void recordsFailuresAndOnlyPositiveOutboxLifecycleCounts() {
        AlertPerformanceMetrics metrics = new AlertPerformanceMetrics(registry);

        metrics.requestReceived(null);
        metrics.failed();
        metrics.outboxLifecycle("alert-delivery", "published", 3);
        metrics.outboxLifecycle("alert-delivery", "ignored", 0);
        metrics.outboxLifecycle("alert-delivery", "ignored", -1);

        assertThat(registry.find("socp.alert.persistence").tag("outcome", "failed")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("socp.alert.outbox.lifecycle")
                .tag("outbox", "alert-delivery").tag("outcome", "published")
                .counter().count()).isEqualTo(3);
        assertThat(registry.find("socp.alert.stage").tag("stage", "transport_request")
                .timer()).isNull();
    }
}
