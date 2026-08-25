package com.socp.search.config.persistence.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchCacheProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.OsEventWriter;
import com.socp.search.config.persistence.entity.SearchEventEntity;
import com.socp.search.config.persistence.repository.SearchEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import com.socp.platform.tenant.context.TenantContext;

/**
 * 检索事件库——本地切片用 H2 文件库（重启不丢，含种子样例）；生产由 OpenSearch 承载。
 * 内存中仅保留最近的有界窗口供 SPL 引擎快速检索，写入同时落库。
 */
@Component
public class SearchStore {

    private final SearchEventRepository repo;
    private final OsEventWriter osWriter;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CAP = 20000;

    private final Map<String, TenantBuffer> eventsByTenant = new ConcurrentHashMap<>();

    private final long tenantBufferIdleTtlMs;
    private final int maxTenantBuffers;

    public SearchStore(SearchEventRepository repo, OsEventWriter osWriter) {
        this(repo, osWriter, new SearchCacheProperties());
    }

    @Autowired
    public SearchStore(SearchEventRepository repo, OsEventWriter osWriter,
                       SearchCacheProperties properties) {
        this.repo = repo;
        this.osWriter = osWriter;
        this.tenantBufferIdleTtlMs = properties.getIdleTtlMs();
        this.maxTenantBuffers = properties.getMaxTenants();
        long persisted = repo.countByTenantId("default");
        if (persisted == 0) {
            seed();
        } else {
            events("default");
        }
    }

    public List<SearchEvent> all() {
        return events(currentTenant()).snapshot();
    }

    /** 采集管线写入的归一化事件（真实接入数据），同时落库。超出容量时丢弃最旧。 */
    public void ingest(SearchEvent e) {
        repo.save(toEntity(e));
        remember(e);
        // 生产检索库：OpenSearch 异步落索引（best-effort，失败静默；null=单测跳过）
        if (osWriter != null) osWriter.writeEvents(List.of(e));
    }

    /** 批量写入（攒批场景）：一次 saveAll 落库，避免逐条 H2 insert 拖慢采集吞吐。 */
    public void ingestBatch(List<SearchEvent> es) {
        saveBatch(es);
        // 生产检索库：OpenSearch 异步落索引（best-effort，失败静默；null=单测跳过）。
        // 2026-08-12 P2：Kafka 可用时 OS 由 OsIndexerConsumer 消费写入（可重放），此直写仅作无 Kafka 回退。
        if (osWriter != null) osWriter.writeEvents(es);
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
        events(eventTenant(event)).remember(event);
    }

    private static SearchEvent ev(Instant ts, String source, String host, String severity, String msg, Map<String, String> fields) {
        Map<String, String> f = new LinkedHashMap<>(fields);
        return new SearchEvent(ts, source, host, severity, msg, Map.copyOf(f));
    }

    // ---- 互转 ----

    public static SearchEventEntity toEntity(SearchEvent e) {
        SearchEventEntity en = new SearchEventEntity();
        en.setEventId(e.eventId());
        en.setTimestamp(e.timestamp());
        en.setSource(e.source());
        en.setHost(e.host());
        en.setSeverity(e.severity());
        en.setMsg(e.msg());
        en.setFieldsJson(writeJson(e.fields()));
        en.setEcsJson(writeJson(e.ecs()));
        en.setTenantId(eventTenant(e));
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
        TenantBuffer buffer = eventsByTenant.computeIfAbsent(tenant, ignored -> new TenantBuffer(CAP));
        buffer.touch();
        buffer.initialize(tenant, repo);
        return buffer;
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
    }

    int cachedTenantBuffers() {
        return eventsByTenant.size();
    }

    /** Tenant-local bounded insertion-ordered index; writes no longer block unrelated tenants. */
    private static final class TenantBuffer {
        private final int cap;
        private final LinkedHashMap<String, SearchEvent> events = new LinkedHashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean initialized;
        private volatile long lastAccessMillis = System.currentTimeMillis();

        private TenantBuffer(int cap) {
            this.cap = cap;
        }

        private void initialize(String tenant, SearchEventRepository repo) {
            if (initialized) return;
            lock.lock();
            try {
                if (initialized) return;
                List<SearchEventEntity> recent = repo.findTop20000ByTenantIdOrderByTimestampDesc(tenant);
                if (recent != null) {
                    for (int i = recent.size() - 1; i >= 0; i--) {
                        SearchEvent event = fromEntity(recent.get(i));
                        events.put(event.eventId(), event);
                    }
                }
                initialized = true;
            } finally {
                lock.unlock();
            }
        }

        private void remember(SearchEvent event) {
            touch();
            lock.lock();
            try {
                events.remove(event.eventId());
                events.put(event.eventId(), event);
                while (events.size() > cap) events.remove(events.keySet().iterator().next());
            } finally {
                lock.unlock();
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

        private void touch() {
            lastAccessMillis = System.currentTimeMillis();
        }
    }

    private static String eventTenant(SearchEvent event) {
        String tenant = event.fields().get("tenant_id");
        if (tenant == null || tenant.isBlank()) tenant = event.fields().get("tenantId");
        return tenant == null || tenant.isBlank() ? currentTenant() : tenant;
    }

    private static String currentTenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
