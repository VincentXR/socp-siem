package com.socp.alert.service;


import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Alert Web side of the T6 (request received) to T7 (DB committed) contract. */
@Component
public class AlertPerformanceMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicLong> backlogGauges = new ConcurrentHashMap<>();

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

    /** Records one bounded drain window without creating per-row metric cardinality. */
    public void outboxDrain(String outbox, int rounds, long durationNanos) {
        registry.summary("socp.alert.outbox.drain.rounds", "outbox", outbox)
                .record(Math.max(0, rounds));
        Timer.builder("socp.alert.outbox.drain.duration")
                .tag("outbox", outbox)
                .maximumExpectedValue(Duration.ofMinutes(1))
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    /** Current-state gauges complement lifecycle counters, which never decrease on replay. */
    public void outboxBacklog(String outbox, long pendingCount, Instant oldestPending,
                              long deadCount, Instant oldestDead) {
        Instant now = Instant.now();
        gauge("socp.alert.outbox.pending.count", outbox).set(Math.max(0L, pendingCount));
        gauge("socp.alert.outbox.oldest.pending.age.seconds", outbox)
                .set(ageSeconds(oldestPending, now));
        gauge("socp.alert.outbox.dead.count", outbox).set(Math.max(0L, deadCount));
        gauge("socp.alert.outbox.oldest.dead.age.seconds", outbox)
                .set(ageSeconds(oldestDead, now));
    }

    private AtomicLong gauge(String name, String outbox) {
        String key = name + ':' + outbox;
        return backlogGauges.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::get)
                    .tag("outbox", outbox)
                    .register(registry);
            return value;
        });
    }

    private static long ageSeconds(Instant value, Instant now) {
        return value == null ? 0L : Math.max(0L, Duration.between(value, now).toSeconds());
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
