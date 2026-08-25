package com.socp.alert.service;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Alert Web side of the T6 (request received) to T7 (DB committed) contract. */
@Component
public class AlertPerformanceMetrics {

    private final MeterRegistry registry;

    public AlertPerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Sample requestReceived(Instant outboxClaimedAt) {
        Instant now = Instant.now();
        if (outboxClaimedAt != null) {
            record("transport_request", Duration.between(outboxClaimedAt, now));
        }
        return new Sample(System.nanoTime(), now);
    }

    /** Called after the transactional service proxy has returned, hence after commit. */
    public void committed(Sample sample, Instant triggerIngestedAt) {
        if (sample == null) return;
        Instant committedAt = Instant.now();
        recordNanos("alert_persistence", System.nanoTime() - sample.receivedNanos());
        if (triggerIngestedAt != null) {
            record("durable_alert", Duration.between(triggerIngestedAt, committedAt));
        }
        registry.counter("socp.alert.db.transactions", "scope", "alert",
                "operation", "create").increment();
    }

    public void failed() {
        registry.counter("socp.alert.persistence", "outcome", "failed").increment();
    }

    /**
     * Records operational lifecycle transitions for durable delivery rows.
     * Tags deliberately use a small fixed vocabulary so an incident cannot
     * create unbounded metric-cardinality through an error message or id.
     */
    public void outboxLifecycle(String outbox, String outcome, int count) {
        if (count <= 0) return;
        registry.counter("socp.alert.outbox.lifecycle", "outbox", outbox,
                "outcome", outcome).increment(count);
    }

    private void record(String stage, Duration duration) {
        if (duration == null) return;
        recordNanos(stage, Math.max(0L, duration.toNanos()));
    }

    private void recordNanos(String stage, long nanos) {
        Timer.builder("socp.alert.stage")
                .tag("stage", stage)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    public record Sample(long receivedNanos, Instant receivedAt) {
    }
}
