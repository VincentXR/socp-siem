package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchCacheProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.OsEventWriter;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;
import com.socp.platform.tenant.context.TenantContext;

/**
 * 检索事件库——本地切片用 H2 文件库（重启不丢）；生产由 OpenSearch 承载。
 * 内存中仅保留最近的有界窗口供 SPL 引擎快速检索，写入同时落库。
 * 伪造的演示事件只在 {@code socp.demo-data.enabled=true} 时写入，prod profile 强制关闭。
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class SearchStore {

    private final SearchEventRepository repo;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CAP = 20000;

    private final Map<String, TenantBuffer> eventsByTenant = new ConcurrentHashMap<>();

    private final long tenantBufferIdleTtlMs;
    private final int maxTenantBuffers;
    private final int warmupMaxEvents;
    private final int warmupBatchSize;
    private final Semaphore warmupPermits;
    private final long maxBytesPerTenant;
    private final long maxBytesTotal;

    /** Source-compatible constructor retained for direct Java integrations; it never seeds. */
    public SearchStore(SearchEventRepository repo, OsEventWriter ignoredWriter) {
        this(repo, new SearchCacheProperties(), false);
    }

    /** Explicit wiring used by tests that need the demo window present. */
    public SearchStore(SearchEventRepository repo, SearchCacheProperties properties,
                       boolean demoDataEnabled) {
        this.repo = repo;
        properties.validate();
        this.tenantBufferIdleTtlMs = properties.getIdleTtlMs();
        this.maxTenantBuffers = properties.getMaxTenants();
        this.warmupMaxEvents = Math.min(CAP, properties.getWarmupMaxEvents());
        this.warmupBatchSize = Math.min(this.warmupMaxEvents, properties.getWarmupBatchSize());
        this.warmupPermits = new Semaphore(properties.getMaxConcurrentWarmups(), true);
        this.maxBytesPerTenant = properties.getMaxBytesPerTenant();
        this.maxBytesTotal = properties.getMaxBytesTotal();
        if (!demoDataEnabled) return;
        // Demo fixtures are fake security events. They are only ever written while the
        // socp.demo-data.enabled switch is on, which the prod profile pins to false.
        TenantContext.runWith("default", () -> {
            if (repo.countByTenantId("default") == 0) {
                seed();
            } else {
                events("default");
            }
        });
    }

    @Autowired
    public SearchStore(SearchEventRepository repo, ObjectProvider<OsEventWriter> ignoredWriter,
                       SearchCacheProperties properties,
                       @Value("${socp.demo-data.enabled:false}") boolean demoDataEnabled) {
        this(repo, properties, demoDataEnabled);
    }

    public List<SearchEvent> all() {
        return events(currentTenant()).snapshot();
    }

    /** Legacy direct-ingest compatibility path; durable publication is owned by the Outbox worker. */
    public void ingest(SearchEvent e) {
        repo.save(toEntity(e));
        remember(e);
        // Publication is intentionally not performed here; Kafka/Outbox owns the projection.
    }

    /** Legacy direct-batch compatibility path; it never bypasses Kafka publication. */
    public void ingestBatch(List<SearchEvent> es) {
        saveBatch(es);
    }

    /** 只落 H2（内存 List + repository），不写 OpenSearch——P2 后 OS 走 Kafka 消费侧写入。 */
    public void saveBatch(List<SearchEvent> es) {
        if (es == null || es.isEmpty()) return;
        repo.saveAll(es.stream().map(SearchStore::toEntity).toList());
        rememberBatch(es);
    }

    /** Updates only the bounded local search window after a durable transaction commits. */
    public void rememberBatch(List<SearchEvent> es) {
        if (es == null || es.isEmpty()) return;
        for (SearchEvent event : es) remember(event);
    }

    public int size() {
        return events(currentTenant()).size();
    }

    public long realCount() {
        return repo.countByTenantId(currentTenant());
    }

    private void seed() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.HOURS);
        int i = 0;
        for (int d = 0; d < 7; d++) {
            for (int k = 0; k < 4; k++) {
                save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                        "auth", "web0" + (1 + d % 3), "HIGH",
                        "Failed password for admin from 10.0.0." + (5 + k) + " port 55" + (100 + i),
                        Map.of("src_ip", "10.0.0." + (5 + k), "user", "admin", "action", "deny")));
            }
            save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                    "auth", "web0" + (1 + d % 3), "INFO",
                    "Accepted password for admin from 10.0.0." + (5 + d % 4),
                    Map.of("src_ip", "10.0.0." + (5 + d % 4), "user", "admin", "action", "allow")));
            for (int k = 0; k < 2; k++) {
                save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                        "web", "web01", "INFO",
                        "\"GET /api/v1/users HTTP/1.1\" 200 1234",
                        Map.of("src_ip", "10.0.0." + (20 + k), "http_method", "GET", "url", "/api/v1/users", "bytes", "1234")));
            }
            save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                    "web", "web01", "HIGH",
                    "q=1' OR 1=1 -- SQL injection attempt",
                    Map.of("src_ip", "10.0.0." + (30 + d), "http_method", "POST", "url", "/login", "bytes", "512")));
            for (int k = 0; k < 3; k++) {
                save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                        "firewall", "fw-core", "MEDIUM",
                        "blocked tcp " + "10.0.0." + (40 + k) + ":5555 -> 10.0.0.1:23",
                        Map.of("src_ip", "10.0.0." + (40 + k), "dst_ip", "10.0.0.1", "action", "block", "bytes", "88")));
            }
            if (d % 2 == 0) {
                save(ev(base.minus(d, ChronoUnit.DAYS).plus(i++, ChronoUnit.MINUTES),
                        "auth", "web0" + (1 + d % 3), "CRITICAL",
                        "sudo: admin : TTY=pts/0 ; USER=root ; COMMAND=/bin/su",
                        Map.of("src_ip", "10.0.0." + (5 + d % 4), "user", "admin", "action", "allow")));
            }
        }
    }

    private void save(SearchEvent e) {
        repo.save(toEntity(e));
        remember(e);
    }

    private void remember(SearchEvent event) {
        String tenant = eventTenant(event);
        events(tenant).remember(event);
        evictOverByteBudget(tenant);
    }

    private static SearchEvent ev(Instant ts, String source, String host, String severity, String msg, Map<String, String> fields) {
        Map<String, String> f = new LinkedHashMap<>(fields);
        return new SearchEvent(ts, source, host, severity, msg, Map.copyOf(f));
    }

    // ---- 互转 ----

    public static SearchEventEntity toEntity(SearchEvent e) {
        String tenant = TenantContext.require();
        String declaredTenant = declaredTenant(e);
        if (declaredTenant != null && !declaredTenant.isBlank() && !tenant.equals(declaredTenant)) {
            throw new IllegalArgumentException("event tenant must match the authenticated tenant");
        }
        SearchEventEntity en = new SearchEventEntity();
        en.setEventId(e.eventId());
        en.setPayloadFingerprint(com.socp.search.config.service.IngestionEventIdentity.fingerprint(e));
        en.setTimestamp(e.timestamp());
        en.setSource(e.source());
        en.setHost(e.host());
        en.setSeverity(e.severity());
        en.setMsg(e.msg());
        en.setFieldsJson(writeJson(e.fields()));
        en.setEcsJson(writeJson(e.ecs()));
        en.setTenantId(tenant);
        return en;
    }

    public static SearchEvent fromEntity(SearchEventEntity en) {
        Map<String, String> fields = readMap(en.getFieldsJson());
        Map<String, String> ecs = readMap(en.getEcsJson());
        String eventId = en.getEventId();
        if (eventId == null || eventId.isBlank()) eventId = en.getId();
        return new SearchEvent(eventId, en.getTimestamp(), en.getSource(), en.getHost(), en.getSeverity(),
                en.getMsg(), fields == null ? Map.of() : fields, ecs == null ? Map.of() : ecs);
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }

    private TenantBuffer events(String tenant) {
        TenantBuffer buffer = eventsByTenant.get(tenant);
        if (buffer == null) buffer = admitTenantBuffer(tenant);
        buffer.touch();
        buffer.initialize(tenant, repo, warmupPermits);
        evictOverByteBudget(tenant);
        return buffer;
    }

    /**
     * Cardinality is enforced at admission rather than waiting for the periodic
     * cleanup. The oldest tenant is evicted before a new tenant buffer becomes
     * visible, so a tenant burst cannot temporarily grow this map without bound.
     */
    private synchronized TenantBuffer admitTenantBuffer(String tenant) {
        TenantBuffer existing = eventsByTenant.get(tenant);
        if (existing != null) return existing;
        while (eventsByTenant.size() >= maxTenantBuffers) {
            Map.Entry<String, TenantBuffer> oldest = eventsByTenant.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(value -> value.lastAccessMillis)))
                    .orElse(null);
            if (oldest == null) break;
            eventsByTenant.remove(oldest.getKey(), oldest.getValue());
        }
        TenantBuffer created =
                new TenantBuffer(CAP, maxBytesPerTenant, warmupMaxEvents, warmupBatchSize);
        eventsByTenant.put(tenant, created);
        return created;
    }

    @Scheduled(fixedDelayString = "${socp.search.local-cache.cleanup-interval-ms:60000}")
    void evictIdleTenantBuffers() {
        long now = System.currentTimeMillis();
        long safeTtl = Math.max(60_000L, tenantBufferIdleTtlMs);
        eventsByTenant.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis > safeTtl);
        int excess = eventsByTenant.size() - Math.max(1, maxTenantBuffers);
        if (excess > 0) {
            eventsByTenant.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            java.util.Comparator.comparingLong(value -> value.lastAccessMillis)))
                    .limit(excess)
                    .forEach(entry -> eventsByTenant.remove(entry.getKey(), entry.getValue()));
        }
        evictOverByteBudget(null);
    }

    private synchronized void evictOverByteBudget(String protectedTenant) {
        long total = cachedBytes();
        if (total <= maxBytesTotal) return;
        List<Map.Entry<String, TenantBuffer>> candidates = eventsByTenant.entrySet().stream()
                .filter(entry -> protectedTenant == null || !protectedTenant.equals(entry.getKey()))
                .sorted(Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(value -> value.lastAccessMillis)))
                .toList();
        for (Map.Entry<String, TenantBuffer> entry : candidates) {
            if (total <= maxBytesTotal) break;
            if (eventsByTenant.remove(entry.getKey(), entry.getValue())) {
                total -= entry.getValue().bytes();
            }
        }
    }

    int cachedTenantBuffers() {
        return eventsByTenant.size();
    }

    /**
     * Estimated serialized/event payload weight used only for cache admission.
     * This is not a claim about exact JVM heap retained size.
     */
    long cachedBytes() {
        return eventsByTenant.values().stream().mapToLong(TenantBuffer::bytes).sum();
    }

    /** Tenant-local bounded insertion-ordered index; writes no longer block unrelated tenants. */
    private static final class TenantBuffer {
        private final int cap;
        private final long maxBytes;
        private final int warmupMaxEvents;
        private final int warmupBatchSize;
        private final LinkedHashMap<String, SearchEvent> events = new LinkedHashMap<>();
        private final Map<String, Long> weights = new LinkedHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean initialized;
        private volatile long lastAccessMillis = System.currentTimeMillis();
        private long currentBytes;

        private TenantBuffer(int cap, long maxBytes, int warmupMaxEvents, int warmupBatchSize) {
            this.cap = cap;
            this.maxBytes = maxBytes;
            this.warmupMaxEvents = warmupMaxEvents;
            this.warmupBatchSize = warmupBatchSize;
        }

        /**
         * Load JPA rows in small pages. At most maxConcurrentWarmups tenants may
         * do this at once. Candidate SearchEvent payloads are capped by the
         * tenant's estimated-byte budget; transient entity memory is therefore
         * one warmup page plus at most one currently converted row. A single DB
         * row can still be arbitrarily large, so these estimates are not an
         * absolute JVM-heap bound.
         */
        private void initialize(String tenant, SearchEventRepository repo, Semaphore permits) {
            if (initialized) return;
            boolean acquired = false;
            try {
                permits.acquire();
                acquired = true;
                lock.lock();
                try {
                    if (initialized) return;
                    List<SearchEvent> newestFirst = new ArrayList<>();
                    long candidateBytes = 0L;
                    int examined = 0;
                    int page = 0;
                    boolean budgetFull = false;
                    while (examined < warmupMaxEvents && !budgetFull) {
                        int pageSize = Math.min(warmupBatchSize, warmupMaxEvents - examined);
                        List<SearchEventEntity> recent = repo.findByTenantIdOrderByTimestampDesc(
                                tenant, org.springframework.data.domain.PageRequest.of(page, pageSize));
                        if (recent == null || recent.isEmpty()) break;
                        for (SearchEventEntity entity : recent) {
                            if (examined++ >= warmupMaxEvents) break;
                            SearchEvent event = fromEntity(entity);
                            long weight = estimateEventBytes(event);
                            if (weight > maxBytes) continue;
                            if (newestFirst.size() >= cap || candidateBytes + weight > maxBytes) {
                                budgetFull = true;
                                break;
                            }
                            newestFirst.add(event);
                            candidateBytes += weight;
                        }
                        if (recent.size() < pageSize) break;
                        page++;
                    }
                    for (int i = newestFirst.size() - 1; i >= 0; i--) {
                        putBounded(newestFirst.get(i));
                    }
                    initialized = true;
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) permits.release();
            }
        }

        private void remember(SearchEvent event) {
            touch();
            lock.lock();
            try {
                putBounded(event);
            } finally {
                lock.unlock();
            }
        }

        private void putBounded(SearchEvent event) {
            String id = event.eventId();
            SearchEvent previous = events.remove(id);
            Long previousWeight = weights.remove(id);
            if (previous != null && previousWeight != null) currentBytes -= previousWeight;

            long weight = estimateEventBytes(event);
            // Persistence already happened before remember(). A single event
            // larger than the tenant budget is intentionally cold-only rather
            // than silently breaking the hot-cache budget.
            if (weight > maxBytes) return;

            events.put(id, event);
            weights.put(id, weight);
            currentBytes += weight;
            while (events.size() > cap || currentBytes > maxBytes) {
                String oldest = events.keySet().iterator().next();
                events.remove(oldest);
                Long removedWeight = weights.remove(oldest);
                if (removedWeight != null) currentBytes -= removedWeight;
            }
        }

        private List<SearchEvent> snapshot() {
            touch();
            lock.lock();
            try {
                return List.copyOf(events.values());
            } finally {
                lock.unlock();
            }
        }

        private int size() {
            touch();
            lock.lock();
            try {
                return events.size();
            } finally {
                lock.unlock();
            }
        }

        private long bytes() {
            lock.lock();
            try {
                return currentBytes;
            } finally {
                lock.unlock();
            }
        }

        private static long estimateEventBytes(SearchEvent event) {
            long bytes = 256L;
            bytes += utf8(event.eventId());
            bytes += utf8(event.source());
            bytes += utf8(event.host());
            bytes += utf8(event.severity());
            bytes += utf8(event.msg());
            bytes += mapBytes(event.fields());
            bytes += mapBytes(event.ecs());
            return Math.max(1L, bytes);
        }

        private static long mapBytes(Map<String, String> values) {
            if (values == null || values.isEmpty()) return 0L;
            long bytes = 0L;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                bytes += utf8(entry.getKey());
                bytes += utf8(entry.getValue());
                bytes += 32L;
            }
            return bytes;
        }

        private static long utf8(String value) {
            return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
        }

        private void touch() {
            lastAccessMillis = System.currentTimeMillis();
        }
    }

    private static String eventTenant(SearchEvent event) {
        String tenant = declaredTenant(event);
        return tenant == null || tenant.isBlank() ? currentTenant() : tenant;
    }

    private static String declaredTenant(SearchEvent event) {
        if (event == null || event.fields() == null) return null;
        String tenant = event.fields().get("tenant_id");
        if (tenant == null || tenant.isBlank()) tenant = event.fields().get("tenantId");
        return tenant;
    }

    private static String currentTenant() {
        return TenantContext.require();
    }
}
