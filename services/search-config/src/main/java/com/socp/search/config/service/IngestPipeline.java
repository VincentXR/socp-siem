package com.socp.search.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.service.DetectClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.config.IngestRuntimeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Batches normalized ingest events into the durable search/Kafka commit boundary. */
@Service
public class IngestPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int BATCH_SIZE = 200;

    private final IngestEventNormalizer normalizer;
    private final IngestionCommitService commitService;
    private final IngestTaskMonitor monitor;
    private final DetectClient detectClient;
    private final Counter acceptedCounter;
    private final Counter skippedCounter;
    private final Counter forwardedCounter;
    private final AtomicReference<Double> eps = new AtomicReference<>(0.0);

    private final boolean forwardHttp;

    @Autowired
    public IngestPipeline(IngestEventNormalizer normalizer, IngestionCommitService commitService,
                          IngestTaskMonitor monitor, DetectClient detectClient,
                          MeterRegistry meterRegistry, IngestRuntimeProperties properties) {
        this.normalizer = normalizer;
        this.commitService = commitService;
        this.monitor = monitor;
        this.detectClient = detectClient;
        this.forwardHttp = properties.isForwardHttp();
        this.acceptedCounter = Counter.builder("socp_ingest_events_total")
                .tag("outcome", "accepted").register(meterRegistry);
        this.skippedCounter = Counter.builder("socp_ingest_events_total")
                .tag("outcome", "skipped").register(meterRegistry);
        this.forwardedCounter = Counter.builder("socp_ingest_events_total")
                .tag("outcome", "forwarded").register(meterRegistry);
        io.micrometer.core.instrument.Gauge.builder("socp_ingest_eps", eps, AtomicReference::get)
                .register(meterRegistry);
    }

    public IngestPipeline(IngestEventNormalizer normalizer, IngestionCommitService commitService,
                          IngestTaskMonitor monitor, DetectClient detectClient,
                          MeterRegistry meterRegistry) {
        this(normalizer, commitService, monitor, detectClient, meterRegistry,
                new IngestRuntimeProperties());
    }

    public Map<String, Object> process(String body) {
        return process(body, null);
    }

    public Map<String, Object> process(String body, String defaultCollector) {
        if (body == null || body.isBlank()) return emptyResult();
        int accepted = 0;
        int skipped = 0;
        int forwarded = 0;
        Map<String, long[]> perCollector = new LinkedHashMap<>();
        List<IngestEventNormalizer.NormalizedEvent> pending = new ArrayList<>(BATCH_SIZE);
        var lines = body.lines().iterator();
        try {
            while (lines.hasNext()) {
                String line = lines.next();
                String raw = line.trim();
                if (raw.isEmpty()) continue;
                long bytes = raw.length();
                IngestEventNormalizer.NormalizedEvent normalized;
                try {
                    normalized = normalizer.normalize(raw, defaultCollector);
                } catch (RuntimeException invalidLine) {
                    skipped++;
                    bump(perCollector, defaultCollector, 0, 1, 0, bytes);
                    log.debug("Ingest line rejected collector={} reason={}",
                            defaultCollector, invalidLine.toString());
                    continue;
                }
                // Bytes are received as soon as a line is parsed. Accepted is
                // credited only after the durable commit boundary succeeds.
                bump(perCollector, normalized.collector(), 0, 0, 0, bytes);
                pending.add(normalized);
                if (pending.size() >= BATCH_SIZE) {
                    FlushResult flushed = flush(pending, perCollector);
                    accepted += flushed.accepted();
                    forwarded += flushed.forwarded();
                }
            }
            FlushResult flushed = flush(pending, perCollector);
            accepted += flushed.accepted();
            forwarded += flushed.forwarded();
        } catch (PersistenceFailure failure) {
            recordMetrics(accepted, skipped, forwarded, perCollector);
            throw new ApiException(503,
                    "Ingest persistence is unavailable; retry the uncommitted batch",
                    failure.getCause());
        }
        recordMetrics(accepted, skipped, forwarded, perCollector);
        return result(accepted, skipped, forwarded, perCollector, defaultCollector);
    }

    private FlushResult flush(List<IngestEventNormalizer.NormalizedEvent> batch,
                              Map<String, long[]> perCollector) {
        if (batch.isEmpty()) return new FlushResult(0, 0);
        try {
            commitService.commit(batch.stream().map(IngestEventNormalizer.NormalizedEvent::event).toList());
        } catch (RuntimeException persistenceFailure) {
            throw new PersistenceFailure(persistenceFailure);
        }
        int accepted = batch.size();
        for (IngestEventNormalizer.NormalizedEvent event : batch) {
            bump(perCollector, event.collector(), 1, 0, 0, 0);
        }
        int forwarded = forwardHttp ? forwardForDebug(batch) : 0;
        int credited = Math.min(forwarded, batch.size());
        for (int index = 0; index < credited; index++) {
            bump(perCollector, batch.get(index).collector(), 0, 0, 1, 0);
        }
        batch.clear();
        return new FlushResult(accepted, forwarded);
    }

    private int forwardForDebug(List<IngestEventNormalizer.NormalizedEvent> batch) {
        StringBuilder ndjson = new StringBuilder();
        try {
            for (var event : batch) {
                ndjson.append(MAPPER.writeValueAsString(event.payload())).append('\n');
            }
        } catch (JsonProcessingException serializationFailure) {
            log.warn("Debug HTTP forwarding skipped because the normalized batch cannot be serialized: {}",
                    serializationFailure.getOriginalMessage());
            return 0;
        }
        ServiceCall call;
        try {
            call = detectClient.ingestBulk(ndjson.toString());
        } catch (RuntimeException forwardingFailure) {
            log.warn("Debug HTTP forwarding failed after durable ingest: {}", forwardingFailure.getMessage());
            return 0;
        }
        if (call == null || !call.ok()) {
            log.warn("Debug HTTP forwarding to Detection failed: {}",
                    call == null ? "no service result" : call.failureReason());
            return 0;
        }
        if (call.body() == null || call.body().isBlank()) return 0;
        try {
            return Math.max(0, MAPPER.readTree(call.body()).path("accepted").asInt(0));
        } catch (JsonProcessingException invalidResponse) {
            log.warn("Detection debug forwarding returned invalid JSON: {}",
                    invalidResponse.getOriginalMessage());
            return 0;
        }
    }

    private Map<String, Object> result(int accepted, int skipped, int forwarded,
                                       Map<String, long[]> perCollector, String defaultCollector) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("skipped", skipped);
        result.put("forwarded", forwarded);
        result.put("queueLoad", 0.0);
        double total = accepted + skipped;
        result.put("parseFailureRate", total == 0 ? 0.0
                : Math.round(skipped * 1000.0 / total) / 10.0);
        if (defaultCollector != null) {
            Object rate = monitor.runtime(defaultCollector, true).get("eps1m");
            if (rate instanceof Number number) {
                eps.set(number.doubleValue());
                result.put("eps1m", number.doubleValue());
            }
        }
        result.put("collectors", perCollector.keySet());
        return result;
    }

    private static Map<String, Object> emptyResult() {
        return Map.of("accepted", 0, "skipped", 0, "forwarded", 0);
    }

    private static void bump(Map<String, long[]> counters, String collector,
                             long accepted, long skipped, long forwarded, long bytes) {
        String key = collector == null || collector.isBlank() ? "unknown" : collector;
        long[] values = counters.computeIfAbsent(key, ignored -> new long[4]);
        values[0] += accepted;
        values[1] += skipped;
        values[2] += forwarded;
        values[3] += bytes;
    }

    private void recordMetrics(int accepted, int skipped, int forwarded,
                               Map<String, long[]> perCollector) {
        perCollector.forEach((collector, values) -> monitor.record(
                collector, (int) values[0], (int) values[1], (int) values[2], values[3]));
        acceptedCounter.increment(accepted);
        skippedCounter.increment(skipped);
        forwardedCounter.increment(forwarded);
    }

    private record FlushResult(int accepted, int forwarded) {
    }

    private static final class PersistenceFailure extends RuntimeException {
        private PersistenceFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
