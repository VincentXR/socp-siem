package com.socp.threat.web.persistence.store;


import com.socp.threat.web.persistence.repository.IocRepository;
import com.socp.threat.web.persistence.entity.IocEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.domain.IocIdentity;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import com.socp.platform.tenant.persistence.TenantSystemJob;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.Locale;

/**
 * Tenant-scoped source facts in PostgreSQL (H2 for local development).
 * Matching reads committed lifecycle state; no replica-local IOC cache may outlive revocation.
 */
@Component
public class IocStore {

    private final IocRepository repo;
    private final boolean demoDataEnabled;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MATCH_VALUES = 1000;

    public IocStore(IocRepository repo) {
        this(repo, true);
    }

    @Autowired
    public IocStore(IocRepository repo,
                    @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repo = repo;
        this.demoDataEnabled = demoDataEnabled;
    }

    @PostConstruct
    void seed() {
        if (!demoDataEnabled) return;
        com.socp.platform.tenant.context.TenantContext.runWith("default", () -> {
        if (repo.countByTenantId("default") > 0) return;
        add(Ioc.of("IP", "45.146.165.37", "CRITICAL", "demo-feed", "已知 C2 回连地址", List.of("demo", "c2", "malware")));
        add(Ioc.of("IP", "185.220.101.1", "HIGH", "demo-feed", "Tor 出口节点", List.of("demo", "tor", "anonymizer")));
        add(Ioc.of("IP", "10.0.0.66", "HIGH", "demo-feed", "内网失陷主机（模拟）", List.of("demo", "compromised")));
        add(Ioc.of("DOMAIN", "malware-c2.example.com", "CRITICAL", "demo-feed", "C2 域名", List.of("demo", "c2", "malware")));
        add(Ioc.of("DOMAIN", "phishing-bank.example.net", "HIGH", "demo-feed", "钓鱼域名", List.of("demo", "phishing")));
        add(Ioc.of("URL", "http://45.146.165.37/payload.bin", "CRITICAL", "demo-feed", "恶意载荷下载", List.of("demo", "malware", "c2")));
        add(Ioc.of("SHA256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "HIGH", "demo-feed", "可疑样本哈希", List.of("demo", "malware")));
        add(Ioc.of("EMAIL", "attacker@evil.com", "MEDIUM", "demo-feed", "攻击者邮箱", List.of("demo", "phishing")));
        });
    }

    @Transactional
    public Ioc add(Ioc ioc) {
        String tenant = tenant();
        String identity = IocIdentity.key(ioc.type(), ioc.value(), ioc.source(), ioc.externalId());
        IocEntity entity = ioc.id() == null ? null : repo.findByIdAndTenantId(ioc.id(), tenant).orElse(null);
        if (entity == null) entity = repo.findByTenantIdAndIdentityKey(tenant, identity).orElseGet(IocEntity::new);
        // Never derive a primary key from an untrusted value, feed name or another tenant's ID.
        if (entity.getId() == null) entity.setId(java.util.UUID.randomUUID().toString());
        Instant existingFirstSeen = entity.getFirstSeen();
        Instant existingLastSeen = entity.getLastSeen();
        Instant incomingFirstSeen = ioc.firstSeen();
        Instant incomingLastSeen = ioc.lastSeen();
        copy(toEntity(ioc), entity);
        entity.setIdentityKey(identity);
        entity.setType(ioc.type().trim().toUpperCase(Locale.ROOT));
        entity.setValue(ioc.value().trim().toLowerCase(Locale.ROOT));
        entity.setSource(ioc.source() == null || ioc.source().isBlank() ? "manual" : ioc.source().trim());
        entity.setExternalId(ioc.externalId() == null || ioc.externalId().isBlank() ? null : ioc.externalId().trim());
        entity.setFirstSeen(earliest(existingFirstSeen, incomingFirstSeen));
        entity.setLastSeen(latest(existingLastSeen, incomingLastSeen));
        entity.setTenantId(tenant);
        IocEntity saved = repo.save(entity);
        return fromEntity(saved == null ? entity : saved);
    }

    public List<Ioc> list(String type) {
        // 租户隔离：只返回当前租户 IOC（无上下文按 default）
        String tenant = com.socp.platform.tenant.context.TenantContext.require();
        final String t = tenant;
        List<Ioc> all = new ArrayList<>();
        for (IocEntity e : repo.findByTenantId(t)) all.add(fromEntity(e));
        if (type == null || type.isBlank()) return all;
        return all.stream().filter(i -> i.type().equalsIgnoreCase(type)).toList();
    }

    /** Reads a bounded tenant page; type and text filtering stay in the database. */
    public Page<Ioc> page(String type, int page, int size, String query) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.asc("type"), Sort.Order.asc("value"), Sort.Order.asc("id")));
        String normalizedType = type == null ? "" : type.trim();
        String normalizedQuery = query == null ? "" : query.trim();
        return repo.searchPage(tenant(), normalizedType, normalizedQuery, pageable)
                .map(IocStore::fromEntity);
    }

    public Ioc get(String id) {
        if (id == null || id.isBlank()) return null;
        return repo.findByIdAndTenantId(id, tenant()).map(IocStore::fromEntity).orElse(null);
    }

    public boolean delete(String id) {
        var entity = repo.findByIdAndTenantId(id, tenant());
        if (entity.isEmpty()) return false;
        IocEntity e = entity.get();
        repo.delete(e);
        return true;
    }

    /** Exact matching observes the database lifecycle on every request. */
    public Ioc match(String value) {
        if (value == null || value.isBlank()) return null;
        return matchAll(List.of(value)).get(value);
    }

    /** One indexed query for the batch; each value selects the highest-risk active source. */
    public Map<String, Ioc> matchAll(List<String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > MAX_MATCH_VALUES) throw new IllegalArgumentException("at most 1000 IOC values may be matched");
        Map<String, Ioc> out = new LinkedHashMap<>();
        List<String> normalized = values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).distinct().toList();
        if (normalized.isEmpty()) return Map.of();
        Instant at = Instant.now();
        Map<String, Ioc> active = new LinkedHashMap<>();
        for (IocEntity entity : repo.findActiveMatches(tenant(), normalized, at)) {
            Ioc ioc = fromEntity(entity);
            if (ioc.isActiveAt(at)) active.put(ioc.value(), ioc);
        }
        for (String value : values) {
            if (value == null) continue;
            Ioc ioc = active.get(value.trim().toLowerCase(Locale.ROOT));
            if (ioc != null) out.put(value, ioc);
        }
        return out;
    }

    public long count() {
        return repo.countByTenantId(tenant());
    }

    public List<Ioc> all() {
        String tenant = tenant();
        List<Ioc> out = new ArrayList<>();
        for (IocEntity e : repo.findByTenantId(tenant)) out.add(fromEntity(e));
        return out;
    }

    /**
     * Removes only expired, non-revoked feed indicators. Revoked indicators
     * are retained as audit evidence and remain visible through list/all.
     * The tenant system-job aspect installs an explicit cross-tenant scope.
     */
    @Scheduled(fixedDelayString = "${socp.threat.ioc.expiry-cleanup-ms:3600000}")
    @TenantSystemJob
    @Transactional
    public void cleanupExpired() {
        Instant now = Instant.now();
        repo.deleteByExpirationBeforeAndRevokedFalse(now);
        repo.deleteByValidUntilBeforeAndRevokedFalse(now);
    }

    private static String tenant() {
        return com.socp.platform.tenant.context.TenantContext.require();
    }

    private static Instant earliest(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private static Instant latest(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isAfter(second) ? first : second;
    }

    // ---- 互转 ----

    static IocEntity toEntity(Ioc i) {
        IocEntity e = new IocEntity();
        e.setId(i.id());
        e.setType(i.type());
        e.setValue(i.value());
        e.setSeverity(i.severity());
        e.setSource(i.source());
        e.setDescription(i.description());
        e.setTagsJson(writeJson(i.tags()));
        e.setFirstSeen(i.firstSeen());
        e.setLastSeen(i.lastSeen());
        e.setFeed(i.feed());
        e.setExternalId(i.externalId());
        e.setConfidence(i.confidence());
        e.setTlp(i.tlp());
        e.setValidFrom(i.validFrom());
        e.setValidUntil(i.validUntil());
        e.setExpiration(i.expiration());
        e.setRevoked(i.revoked());
        e.setProvenance(i.provenance());
        return e;
    }

    static Ioc fromEntity(IocEntity e) {
        List<String> tags = readList(e.getTagsJson());
        return new Ioc(e.getId(), e.getType(), e.getValue(), e.getSeverity(), e.getSource(),
                e.getDescription(), tags == null ? List.of() : tags, e.getFirstSeen(), e.getLastSeen(),
                e.getFeed(), e.getExternalId(), e.getConfidence(), e.getTlp(), e.getValidFrom(),
                e.getValidUntil(), e.getExpiration(), e.isRevoked(), e.getProvenance());
    }

    private static void copy(IocEntity source, IocEntity target) {
        if (target.getId() == null || target.getId().isBlank()) target.setId(source.getId());
        target.setType(source.getType());
        target.setValue(source.getValue());
        target.setSeverity(source.getSeverity());
        target.setSource(source.getSource());
        target.setDescription(source.getDescription());
        target.setTagsJson(source.getTagsJson());
        target.setFirstSeen(source.getFirstSeen());
        target.setLastSeen(source.getLastSeen());
        target.setFeed(source.getFeed());
        target.setExternalId(source.getExternalId());
        target.setConfidence(source.getConfidence());
        target.setTlp(source.getTlp());
        target.setValidFrom(source.getValidFrom());
        target.setValidUntil(source.getValidUntil());
        target.setExpiration(source.getExpiration());
        target.setRevoked(source.isRevoked());
        target.setProvenance(source.getProvenance());
    }

    private static String writeJson(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }
}
