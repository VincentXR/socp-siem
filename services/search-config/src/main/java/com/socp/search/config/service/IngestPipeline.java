package com.socp.search.config.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.service.DetectClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.config.SearchRuntimeRole;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicReference;

/** Batches normalized ingest events into the durable search/Kafka commit boundary. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
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
        return process(body, defaultCollector, null);
    }

    /**
     * Process a batch with an optional HTTP idempotency key.  The key is
     * scoped to the request body by contract; each non-empty line receives a
     * deterministic suffix and payload fingerprint so retries preserve
     * producer event identity while a reused key with a different body does
     * not hide a real event. Genuine duplicate logs without a key remain
     * separate events.
     */
    public Map<String, Object> process(String body, String defaultCollector, String idempotencyKey) {
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.trim();
            if (idempotencyKey.length() > 256) {
                throw new ApiException(400, "Idempotency-Key must not exceed 256 characters");
            }
            if (idempotencyKey.isBlank()) idempotencyKey = null;
        }
        if (body == null || body.isBlank()) return emptyResult();
        int accepted = 0;
        int created = 0;
        int duplicates = 0;
        int skipped = 0;
        int forwarded = 0;
        Map<String, long[]> perCollector = new LinkedHashMap<>();
        List<IngestEventNormalizer.NormalizedEvent> pending = new ArrayList<>(BATCH_SIZE);
        var lines = body.lines().iterator();
        try {
            int lineNumber = 0;
            while (lines.hasNext()) {
                String line = lines.next();
                int currentLine = lineNumber++;
                String raw = line.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                long bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                // Bytes are received as soon as a line is parsed. Accepted is
                // credited only after the durable commit boundary succeeds;
                // this also keeps dependency failures visible in collector
                // resource metrics without classifying them as parse skips.
                bump(perCollector, defaultCollector, 0, 0, 0, bytes);
                IngestEventNormalizer.NormalizedEvent normalized;
                try {
                    String stableId = idempotencyKey == null ? null
                            : stableBatchIdentity(idempotencyKey, currentLine, raw, defaultCollector);
                    normalized = idempotencyKey == null
                            ? normalizer.normalize(raw, defaultCollector)
                            : normalizer.normalize(raw, defaultCollector, stableId);
                } catch (IngestParseException invalidLine) {
                    skipped++;
                    bump(perCollector, defaultCollector, 0, 1, 0, bytes);
                    log.debug("Ingest line rejected collector={} reason={}",
                            defaultCollector, invalidLine.toString());
                    continue;
                } catch (RuntimeException dependencyFailure) {
                    // Source resolution, rule lookup, reference-set enrichment,
                    // and tenant admission all touch durable/shared state. They
                    // are not parse failures: acknowledging them as skipped
                    // would lose the line while returning an apparently valid
                    // ingest response. Let the outer boundary convert this to
                    // HTTP 503 and keep the current uncommitted batch retryable.
                    throw new PersistenceFailure(dependencyFailure);
                }
                pending.add(normalized);
                if (pending.size() >= BATCH_SIZE) {
                    FlushResult flushed = flush(pending, perCollector);
                    accepted += flushed.accepted();
                    created += flushed.created();
                    duplicates += flushed.duplicates();
                    forwarded += flushed.forwarded();
                }
            }
            FlushResult flushed = flush(pending, perCollector);
            accepted += flushed.accepted();
            created += flushed.created();
            duplicates += flushed.duplicates();
            forwarded += flushed.forwarded();
        } catch (IngestionIdentityConflictException conflict) {
            recordMetrics(accepted, skipped, forwarded, perCollector);
            throw new ApiException(409, conflict.getMessage(), conflict);
        } catch (PersistenceFailure failure) {
            recordMetrics(accepted, skipped, forwarded, perCollector);
            throw new ApiException(503,
                    "Ingest persistence is unavailable; retry the uncommitted batch",
                    failure.getCause());
        }
        recordMetrics(accepted, skipped, forwarded, perCollector);
        return result(accepted, created, duplicates, skipped, forwarded, perCollector, defaultCollector);
    }

    private static String stableBatchIdentity(String key, int lineNumber, String raw, String collector) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String tenant = com.socp.platform.tenant.context.TenantContext.get();
            String namespace = (tenant == null || tenant.isBlank() ? "default" : tenant)
                    + "|" + (collector == null || collector.isBlank() ? "unknown" : collector);
            byte[] bytes = digest.digest((namespace + "|" + raw)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) fingerprint.append(String.format("%02x", value & 0xff));
            return key + ":" + lineNumber + ":" + fingerprint;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private FlushResult flush(List<IngestEventNormalizer.NormalizedEvent> batch,
                              Map<String, long[]> perCollector) {
        if (batch.isEmpty()) return new FlushResult(0, 0, 0, 0);
        IngestionCommitService.CommitResult committed;
        try {
            committed = commitService.commit(batch.stream()
                    .map(IngestEventNormalizer.NormalizedEvent::event).toList());
        } catch (IngestionIdentityConflictException conflict) {
            throw conflict;
        } catch (RuntimeException persistenceFailure) {
            throw new PersistenceFailure(persistenceFailure);
        }
        // A few source-compatible integrations still mock the pre-result
        // commit method and therefore return null. The production service
        // always returns a non-null result; preserve the old contract here.
        if (committed == null) {
            committed = new IngestionCommitService.CommitResult(batch.size(), batch.size(), 0, 0);
        }
        int accepted = committed.acknowledged();
        for (IngestEventNormalizer.NormalizedEvent event : batch) {
            bump(perCollector, event.collector(), 1, 0, 0, 0);
        }
        int forwarded = forwardHttp ? forwardForDebug(batch) : 0;
        int credited = Math.min(forwarded, batch.size());
        for (int index = 0; index < credited; index++) {
            bump(perCollector, batch.get(index).collector(), 0, 0, 1, 0);
        }
        batch.clear();
        return new FlushResult(accepted, committed.created(), committed.duplicates(), forwarded);
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
            // detect-web /ingest/bulk now wraps its payload in the ApiResult envelope.
            return Math.max(0, MAPPER.readTree(call.body()).path("data").path("accepted").asInt(0));
        } catch (JsonProcessingException invalidResponse) {
            log.warn("Detection debug forwarding returned invalid JSON: {}",
                    invalidResponse.getOriginalMessage());
            return 0;
        }
    }

    private Map<String, Object> result(int accepted, int created, int duplicates, int skipped, int forwarded,
                                       Map<String, long[]> perCollector, String defaultCollector) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("created", created);
        result.put("duplicates", duplicates);
        result.put("skipped", skipped);
        result.put("forwarded", forwarded);
        result.put("acknowledged", accepted);
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
        return Map.of(
                "accepted", 0,
                "created", 0,
                "duplicates", 0,
                "acknowledged", 0,
                "skipped", 0,
                "forwarded", 0
        );
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

    private record FlushResult(int accepted, int created, int duplicates, int forwarded) {
    }

    private static final class PersistenceFailure extends RuntimeException {
        private PersistenceFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
