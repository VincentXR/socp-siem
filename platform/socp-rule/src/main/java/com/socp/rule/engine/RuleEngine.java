package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-worker stateful rule engine.
 *
 * <p>The legacy {@link #ingest(SecurityEvent)} method keeps its non-blocking
 * admission contract. Kafka uses {@link #ingestAndAwait(SecurityEvent)} so the
 * caller receives a completion signal only after every durable sink has
 * returned. This distinction is what lets the transport commit offset lag
 * behind business processing without serialising the Kafka poll loop.</p>
 */
public final class RuleEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final SecurityEvent POISON_EVENT = new SecurityEvent(
            Instant.EPOCH, "POISON", "POISON", "POISON", Map.of(), Severity.INFO);

    private final AtomicReference<List<Rule>> rulesRef;
    private final List<AlertSink> sinks;
    private final Suppressor suppressor;
    private final BlockingQueue<WorkItem> queue = new ArrayBlockingQueue<>(100_000);
    private volatile boolean running = true;
    private Thread worker;

    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();
    private final AtomicLong dropCount = new AtomicLong();

    /** Immediate queue admission plus an optional durable completion signal. */
    public record Submission(boolean accepted, CompletableFuture<Void> completion) {
    }

    private record WorkItem(SecurityEvent event, CompletableFuture<Void> completion,
                            boolean durable) {
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks) {
        this(rules, sinks, null);
    }

    public RuleEngine(List<Rule> rules, List<AlertSink> sinks, Suppressor suppressor) {
        this.rulesRef = new AtomicReference<>(List.copyOf(rules));
        this.sinks = List.copyOf(sinks);
        this.suppressor = suppressor;
    }

    public void start() {
        worker = Thread.startVirtualThread(this::loop);
    }

    /**
     * Rebuild stateful rule windows without replaying historical alerts.
     * Historical rows supplied here are completed durable events only.
     */
    public void restore(List<SecurityEvent> history) {
        if (history == null || history.isEmpty()) return;
        for (SecurityEvent event : history) {
            for (Rule rule : rulesRef.get()) rule.accept(event);
            for (Rule rule : rulesRef.get()) rule.drain();
        }
        log.info("Detection rule state restored events={}", history.size());
    }

    private void loop() {
        while (running) {
            try {
                WorkItem item = queue.take();
                if (item.event() == POISON_EVENT) break;
                try {
                    process(item);
                    if (item.completion() != null) item.completion().complete(null);
                } catch (Throwable ex) {
                    if (item.completion() != null) {
                        item.completion().completeExceptionally(ex);
                    } else {
                        log.error("Alert processing failed eventId={}: {}",
                                item.event().id(), ex.getMessage(), ex);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void process(WorkItem item) {
        SecurityEvent event = item.event();
        eventCount.incrementAndGet();
        List<Rule> rules = rulesRef.get();
        for (Rule rule : rules) rule.accept(event);

        List<Alert> emitted = new ArrayList<>();
        for (Rule rule : rules) {
            for (Alert alert : rule.drain()) {
                if (suppressor != null && !suppressor.allow(alert)) continue;
                alertCount.incrementAndGet();
                emitted.add(alert);
            }
        }

        for (AlertSink sink : sinks) {
            try {
                if (sink instanceof EventAlertSink eventSink) {
                    // Empty results are intentional: they are still a
                    // successful terminal outcome for the source event.
                    eventSink.publish(event, List.copyOf(emitted));
                } else {
                    for (Alert alert : emitted) sink.publish(alert);
                }
            } catch (RuntimeException ex) {
                if (item.durable()) throw ex;
                // Direct HTTP callers retain the old non-fatal sink behavior.
                // Kafka callers receive the exception through the completion
                // future and must retry without advancing the offset.
                log.error("Alert sink failed eventId={} alerts={}: {}",
                        event.id(), emitted.size(), ex.getMessage(), ex);
            }
        }
    }

    /** Legacy non-blocking ingestion API. */
    public boolean ingest(SecurityEvent event) {
        return submit(event, false).accepted();
    }

    /** Submit an event and complete after durable sinks have finished. */
    public CompletableFuture<Void> ingestAndAwait(SecurityEvent event) {
        return submit(event, true).completion();
    }

    public Submission submit(SecurityEvent event, boolean durable) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        WorkItem item = new WorkItem(event, completion, durable);
        if (queue.offer(item)) return new Submission(true, completion);
        try {
            if (queue.offer(item, 50, TimeUnit.MILLISECONDS)) {
                return new Submission(true, completion);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dropCount.incrementAndGet();
        completion.completeExceptionally(new IllegalStateException("detection queue full"));
        return new Submission(false, completion);
    }

    public double queueLoad() {
        int cap = queue.size() + queue.remainingCapacity();
        return cap == 0 ? 0.0 : (double) queue.size() / cap;
    }

    public void close() {
        running = false;
        queue.offer(new WorkItem(POISON_EVENT, null, false));
        if (worker != null) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        rulesRef.get().forEach(Rule::close);
        sinks.forEach(AlertSink::close);
    }

    public void reload(List<Rule> newRules) {
        List<Rule> old = rulesRef.getAndSet(List.copyOf(newRules));
        old.forEach(Rule::close);
        log.info("Detection rules reloaded count={}", newRules.size());
    }

    public List<Map<String, Object>> ruleStats() {
        return rulesRef.get().stream().map(Rule::stats).toList();
    }

    public long eventCount() {
        return eventCount.get();
    }

    public long alertCount() {
        return alertCount.get();
    }

    public long dropCount() {
        return dropCount.get();
    }

    public long suppressedCount() {
        return suppressor == null ? 0 : suppressor.suppressed();
    }
}
