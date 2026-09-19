package com.socp.detect.web.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance-closure measurements for the event path (all events) and the
 * alert path (only events that emit alerts). Meter tags are deliberately
 * bounded so a long benchmark cannot create one time series per event.
 *
 * <p>The component is also the registry-side owner of two operational surfaces:
 * the routing-mismatch diagnostic reported by the rule engine, which
 * {@code docs/observability-stage-metrics.md} registers as the falsifiable form
 * of the partition-local state contract, and the gauges exported from
 * {@code DetectEngineService.stats()} (rule isolation, mismatch windows and the
 * two state-recovery counters). Each exported gauge carries the name of the
 * stats field it mirrors.</p>
 */
@Component
public class DetectionPerformanceMetrics implements RuleProcessingObserver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INGEST_FIELDS = List.of(
            "socp_bench_ingest_time", "ingested_at", "ingest_time", "socp.ingest_time");

    /**
     * Routing-mismatch diagnostic. Its Prometheus form
     * {@code socp_detection_rule_routing_mismatch} is what makes the
     * partition-local state contract a falsifiable observation instead of a
     * comment: the engine emits one report per rule per window, so the series
     * count is bounded by the rule catalogue and the declared field vocabulary,
     * never by the event rate.
     */
    static final String ROUTING_MISMATCH = "socp.detection.rule.routing.mismatch";

    /** Gauges exported from {@code DetectEngineService.stats()}. */
    static final String ISOLATED_RULES = "socp.detection.rules.isolated.count";
    static final String ROUTING_MISMATCH_WINDOWS = ROUTING_MISMATCH + ".windows";
    static final String WARMED_WITHOUT_HISTORY = "socp.detection.state.recovery.warmed.without.history.count";
    static final String REBUILD_RETRIES = "socp.detection.state.recovery.rebuild.retries.count";

    /** Field names and rule ids are bounded vocabularies; the cap only keeps a
     *  hand-written rule id from becoming an oversized label value. */
    private static final int MAX_TAG_LENGTH = 128;

    /** Upper bound on tenant-tagged stat series one replica exports. */
    static final int MAX_TRACKED_TENANTS = 512;

    private final MeterRegistry registry;
    /**
     * Resolved lazily: {@link DetectEngineService} is constructed with this
     * component as its rule-processing observer, so an eager constructor
     * argument would be a bean cycle. A null provider means no engine stats are
     * exported, which keeps the lightweight constructors usable.
     */
    private final ObjectProvider<DetectEngineService> engineProvider;
    private final Map<String, EventTiming> events = new ConcurrentHashMap<>();
    private final Map<String, AlertTiming> alerts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> longGauges = new ConcurrentHashMap<>();
    private final Set<String> statsTenants = ConcurrentHashMap.newKeySet();

    /** Source-compatible constructor for callers that do not export engine stats. */
    public DetectionPerformanceMetrics(MeterRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public DetectionPerformanceMetrics(MeterRegistry registry,
                                       ObjectProvider<DetectEngineService> engineProvider) {
        this.registry = registry;
        this.engineProvider = engineProvider;
    }

    /** T1: Kafka record has entered the Detection consumer. */
    public void kafkaReceived(SecurityEvent event) {
        if (event == null || event.id() == null) return;
        trackTenant(event.tenantId());
        long now = System.nanoTime();
        events.put(event.scopedId(), new EventTiming(now));
        Instant ingestedAt = ingestTime(event);
        if (ingestedAt != null) {
            recordInstant("socp.detection.event.stage", "kafka_queue", ingestedAt, Instant.now());
        }
    }

    /** T2: the Journal PENDING transaction returned successfully. */
    public void journalCommitted(String eventId) {
        EventTiming timing = events.get(eventId);
        if (timing == null) return;
        long now = System.nanoTime();
        recordNanos("socp.detection.event.stage", "journal", now - timing.receivedNanos);
        timing.journalNanos = now;
        registry.counter("socp.detection.db.transactions", "scope", "event",
                "operation", "journal_claim").increment();
    }

    public void journalCommitted(SecurityEvent event) {
        if (event != null) journalCommitted(event.scopedId());
    }

    public void terminalWithoutEvaluation(String eventId, String outcome) {
        events.remove(eventId);
        registry.counter("socp.detection.event.terminal", "outcome", outcome).increment();
    }

    public void terminalWithoutEvaluation(SecurityEvent event, String outcome) {
        if (event != null) terminalWithoutEvaluation(event.scopedId(), outcome);
    }

    @Override
    public void evaluationCompleted(SecurityEvent event, int emittedAlerts) {
        EventTiming timing = timing(event);
        if (timing == null) return;
        long now = System.nanoTime();
        long start = timing.journalNanos == 0 ? timing.receivedNanos : timing.journalNanos;
        recordNanos("socp.detection.event.stage", "rule_evaluation", now - start);
        timing.evaluationNanos = now;
    }

    @Override
    public void durableSinksCompleted(SecurityEvent event, int emittedAlerts) {
        EventTiming timing = timing(event);
        if (timing == null) return;
        long now = System.nanoTime();
        long start = timing.evaluationNanos == 0
                ? (timing.journalNanos == 0 ? timing.receivedNanos : timing.journalNanos)
                : timing.evaluationNanos;
        recordNanos("socp.detection.event.stage", "durable_completion", now - start);
        recordNanos("socp.detection.event.stage", "consumer_to_durable", now - timing.receivedNanos);
        registry.counter("socp.detection.event.completed", "outcome",
                emittedAlerts > 0 ? "alert" : "no_alert").increment();
        registry.counter("socp.detection.db.transactions", "scope", "event",
                "operation", "outbox_and_completion").increment();
        events.remove(event.scopedId());
    }

    @Override
    public void processingFailed(SecurityEvent event, Throwable failure) {
        if (event != null) events.remove(event.scopedId());
        registry.counter("socp.detection.event.completed", "outcome", "failed").increment();
    }

    /**
     * A rule's declared grouping dimension lost to the dimension this event
     * routes on, so the state that rule accumulates is only a fragment of the
     * entity's history. Counting it per rule and per (declared, actual) field
     * pair is what lets an operator find the rule to repartition instead of
     * reading a WARN log at scale.
     */
    @Override
    public void routingMismatched(SecurityEvent event, String ruleId,
                                  String declaredField, String eventRoutingField) {
        registry.counter(ROUTING_MISMATCH,
                "rule", tagValue(ruleId),
                "declared_field", tagValue(declaredField),
                "event_field", tagValue(eventRoutingField)).increment();
        if (event != null) trackTenant(event.tenantId());
    }

    /** Keeps an unbounded label value out of the registry without throwing on the event path. */
    private static String tagValue(String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        String trimmed = raw.trim();
        return trimmed.length() <= MAX_TAG_LENGTH ? trimmed : trimmed.substring(0, MAX_TAG_LENGTH);
    }

    /**
     * Records a tenant this replica evaluates, which is the only set of
     * tenant-scoped engines a scrape on this replica can honestly report on.
     * Called once per event, so it stays a single hash lookup in steady state.
     */
    private void trackTenant(String tenant) {
        if (tenant == null || !TenantContext.isValid(tenant)) return;
        if (statsTenants.contains(tenant)) return;
        if (statsTenants.size() >= MAX_TRACKED_TENANTS) return;
        statsTenants.add(tenant);
    }

    /**
     * Exports the engine statistics the workbench {@code /stats} view already
     * shows as first-class series, so an alert can watch isolation and state
     * recovery instead of a human scanning a page.
     *
     * <p>{@code stats()} is tenant-scoped and reads durable state, so the scrape
     * runs once per tenant this replica has actually evaluated rather than
     * presenting one tenant's number as a process-wide one. The two recovery
     * counters are replica-wide inside the engine and are therefore exported
     * under a {@code scope="replica-local"} label instead of a tenant one, which
     * matches the {@code stateRecovery.scope} the API reports.</p>
     */
    @Scheduled(fixedDelayString = "${socp.detect.metrics.stats-interval-ms:60000}",
            initialDelayString = "${socp.detect.metrics.stats-initial-delay-ms:60000}")
    void refreshEngineStatGauges() {
        DetectEngineService engine = engineProvider == null ? null : engineProvider.getIfAvailable();
        if (engine == null) return;
        for (String tenant : Set.copyOf(statsTenants)) {
            Map<String, Object> stats;
            try {
                stats = TenantContext.callWith(tenant, engine::stats);
            } catch (RuntimeException unreadable) {
                // A stats read must never fail a scrape tick: the exported gauge
                // keeps its previous value and the next tick retries.
                continue;
            }
            applyEngineStats(tenant, stats);
        }
    }

    /** Applies one tenant's stats snapshot to the exported gauges. */
    void applyEngineStats(String tenant, Map<String, Object> stats) {
        if (stats == null) return;
        taggedGauge(ISOLATED_RULES, "tenant", tenant)
                .set(Math.max(0L, longAt(stats, "isolatedRules")));
        taggedGauge(ROUTING_MISMATCH_WINDOWS, "tenant", tenant)
                .set(Math.max(0L, longAt(stats, "routingMismatchWindows")));
        if (!(stats.get("stateRecovery") instanceof Map<?, ?> recovery)) return;
        taggedGauge(WARMED_WITHOUT_HISTORY, "scope", "replica-local")
                .set(Math.max(0L, longAt(recovery, "warmedWithoutHistory")));
        taggedGauge(REBUILD_RETRIES, "scope", "replica-local")
                .set(Math.max(0L, longAt(recovery, "rebuildRetries")));
    }

    private static long longAt(Map<?, ?> values, String key) {
        return values.get(key) instanceof Number number ? number.longValue() : 0L;
    }

    /** T5: a persisted alert outbox row has been claimed for HTTP delivery. */
    public Instant outboxClaimed(DetectionAlertOutboxEntity event) {
        Instant now = Instant.now();
        if (event == null || event.getAlertId() == null) return now;
        alerts.put(event.getAlertId(), new AlertTiming(System.nanoTime()));
        recordInstant("socp.detection.alert.stage", "outbox_queue", event.getCreatedAt(), now);
        registry.counter("socp.detection.db.transactions", "scope", "alert",
                "operation", "outbox_claim").increment();
        return now;
    }

    /** T8: Detection received Alert Web's HTTP acknowledgement. */
    public void alertAcknowledged(String alertId, ServiceCall call) {
        if (alertId == null) return;
        AlertTiming timing = alerts.remove(alertId);
        if (timing == null) return;
        long nowNanos = System.nanoTime();
        recordNanos("socp.detection.alert.stage", "http_round_trip", nowNanos - timing.claimedNanos);
        Instant committedAt = responseCreatedAt(call);
        if (committedAt != null) {
            recordInstant("socp.detection.alert.stage", "response", committedAt, Instant.now());
        }
    }

    public void alertDeliveryFailed(String alertId) {
        if (alertId != null) alerts.remove(alertId);
        registry.counter("socp.detection.alert.delivery", "outcome", "failed").increment();
    }

    public void outboxStateTransaction(String operation) {
        registry.counter("socp.detection.db.transactions", "scope", "alert",
                "operation", operation).increment();
    }

    /** Records bounded lifecycle outcomes for durable publisher rows. */
    public void outboxLifecycle(String outbox, String outcome, int count) {
        if (count <= 0) return;
        registry.counter("socp.detection.outbox.lifecycle", "outbox", outbox,
                "outcome", outcome).increment(count);
    }

    public void outboxDrain(String outbox, int rounds, long durationNanos) {
        registry.summary("socp.detection.outbox.drain.rounds", "outbox", outbox)
                .record(Math.max(0, rounds));
        Timer.builder("socp.detection.outbox.drain.duration")
                .tag("outbox", outbox)
                .maximumExpectedValue(Duration.ofMinutes(1))
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void outboxBacklog(String outbox, long pendingCount, Instant oldestPending,
                              long deadCount, Instant oldestDead) {
        Instant now = Instant.now();
        gauge("socp.detection.outbox.pending.count", outbox).set(Math.max(0L, pendingCount));
        gauge("socp.detection.outbox.oldest.pending.age.seconds", outbox)
                .set(ageSeconds(oldestPending, now));
        gauge("socp.detection.outbox.dead.count", outbox).set(Math.max(0L, deadCount));
        gauge("socp.detection.outbox.oldest.dead.age.seconds", outbox)
                .set(ageSeconds(oldestDead, now));
    }

    private AtomicLong gauge(String name, String outbox) {
        return taggedGauge(name, "outbox", outbox);
    }

    /**
     * One AtomicLong-backed gauge per (name, tag) pair. The map guards the
     * registry so a repeated scrape never re-registers a meter, and the tag name
     * is part of the key because outbox backlog and engine statistics share this
     * helper with different tags.
     */
    private AtomicLong taggedGauge(String name, String tagName, String tag) {
        String key = name + ':' + tagName + ':' + tag;
        return longGauges.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::get)
                    .tag(tagName, tag)
                    .register(registry);
            return value;
        });
    }

    private static long ageSeconds(Instant value, Instant now) {
        return value == null ? 0L : Math.max(0L, Duration.between(value, now).toSeconds());
    }

    @Scheduled(fixedDelayString = "${socp.detect.metrics.timing-cleanup-interval-ms:60000}")
    void cleanupAbandonedTimings() {
        long cutoff = System.nanoTime() - Duration.ofMinutes(10).toNanos();
        events.entrySet().removeIf(entry -> entry.getValue().receivedNanos < cutoff);
        alerts.entrySet().removeIf(entry -> entry.getValue().claimedNanos < cutoff);
    }

    private EventTiming timing(SecurityEvent event) {
        return event == null || event.id() == null ? null : events.get(event.scopedId());
    }

    private void recordNanos(String name, String stage, long nanos) {
        timer(name, stage).record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    private void recordInstant(String name, String stage, Instant start, Instant end) {
        if (start == null || end == null) return;
        timer(name, stage).record(Math.max(0L, Duration.between(start, end).toNanos()),
                TimeUnit.NANOSECONDS);
    }

    private Timer timer(String name, String stage) {
        return Timer.builder(name)
                .tag("stage", stage)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .publishPercentileHistogram()
                .register(registry);
    }

    private static Instant ingestTime(SecurityEvent event) {
        if (event.fields() == null) return null;
        for (String key : INGEST_FIELDS) {
            String value = event.fields().get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                // Optional collector timestamp; the event remains valid.
            }
        }
        return null;
    }

    private static Instant responseCreatedAt(ServiceCall call) {
        if (call == null || call.body() == null || call.body().isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(call.body());
            JsonNode data = root.path("data");
            String value = data.path("createdAt").asText(null);
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return null;
        }
    }

    private static final class EventTiming {
        private final long receivedNanos;
        private volatile long journalNanos;
        private volatile long evaluationNanos;
        private EventTiming(long receivedNanos) {
            this.receivedNanos = receivedNanos;
        }
    }

    private record AlertTiming(long claimedNanos) {
    }
}
