package com.socp.threat.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.threat.web.domain.Ioc;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 威胁情报 IOC 存储——本地切片用 H2 文件库（重启不丢）；生产由 MISP/OTX 同步至 OpenSearch/PG。
 * 对外公共 API 保持不变。
 */
@Component
public class IocStore {

    private final IocRepository repo;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public IocStore(IocRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void seed() {
        if (repo.countByTenantId("default") > 0) return;
        add(Ioc.of("IP", "45.146.165.37", "CRITICAL", "AlienVault OTX", "已知 C2 回连地址", List.of("c2", "malware")));
        add(Ioc.of("IP", "185.220.101.1", "HIGH", "Tor Exit", "Tor 出口节点", List.of("tor", "anonymizer")));
        add(Ioc.of("IP", "10.0.0.66", "HIGH", "内部研判", "内网失陷主机（模拟）", List.of("compromised")));
        add(Ioc.of("DOMAIN", "malware-c2.example.com", "CRITICAL", "MISP", "C2 域名", List.of("c2", "malware")));
        add(Ioc.of("DOMAIN", "phishing-bank.example.net", "HIGH", "PhishTank", "钓鱼域名", List.of("phishing")));
        add(Ioc.of("URL", "http://45.146.165.37/payload.bin", "CRITICAL", "AlienVault OTX", "恶意载荷下载", List.of("malware", "c2")));
        add(Ioc.of("SHA256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "HIGH", "VirusTotal", "可疑样本哈希", List.of("malware")));
        add(Ioc.of("EMAIL", "attacker@evil.com", "MEDIUM", "内部研判", "攻击者邮箱", List.of("phishing")));
    }

    private final Map<String, Ioc> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public synchronized Ioc add(Ioc ioc) {
        repo.save(toEntity(ioc));
        cache.put(cacheKey(tenant(), ioc.value()), ioc);
        return ioc;
    }

    public List<Ioc> list(String type) {
        // 租户隔离：只返回当前租户 IOC（无上下文按 default）
        String tenant = com.socp.platform.tenant.TenantContext.get();
        if (tenant == null) tenant = "default";
        final String t = tenant;
        List<Ioc> all = new ArrayList<>();
        for (IocEntity e : repo.findByTenantId(t)) all.add(fromEntity(e));
        if (type == null || type.isBlank()) return all;
        return all.stream().filter(i -> i.type().equalsIgnoreCase(type)).toList();
    }

    public boolean delete(String id) {
        var entity = repo.findByIdAndTenantId(id, tenant());
        if (entity.isEmpty()) return false;
        IocEntity e = entity.get();
        repo.delete(e);
        cache.remove(cacheKey(tenant(), e.getValue()));
        return true;
    }

    /** 精确匹配单个值（大小写不敏感），优先读缓存，未命中则查库并回填缓存。 */
    public Ioc match(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        String key = cacheKey(tenant(), normalized);
        Ioc cached = cache.get(key);
        if (cached != null) return cached;

        Ioc found = repo.findByTenantIdAndValue(tenant(), normalized)
                .map(IocStore::fromEntity).orElse(null);
        if (found != null) {
            cache.put(key, found);
        }
        return found;
    }

    /** 批量匹配：通过缓存 + 批量 In-List 单次查询优化，避免循环单条 DB 往返。 */
    public Map<String, Ioc> matchAll(List<String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        String tenant = tenant();
        Map<String, Ioc> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (String val : values) {
            if (val == null || val.isBlank()) continue;
            String normalized = val.trim().toLowerCase();
            Ioc cached = cache.get(cacheKey(tenant, normalized));
            if (cached != null) {
                out.put(val, cached);
            } else {
                missing.add(normalized);
            }
        }

        if (!missing.isEmpty()) {
            List<String> distinctMissing = missing.stream().distinct().toList();
            List<IocEntity> foundEntities = repo.findByTenantIdAndValueIn(tenant, distinctMissing);
            for (IocEntity entity : foundEntities) {
                Ioc ioc = fromEntity(entity);
                String normalizedVal = entity.getValue().toLowerCase();
                cache.put(cacheKey(tenant, normalizedVal), ioc);
                for (String originalVal : values) {
                    if (originalVal != null && originalVal.trim().equalsIgnoreCase(normalizedVal)) {
                        out.put(originalVal, ioc);
                    }
                }
            }
        }

        return out;
    }

    private static String cacheKey(String tenant, String value) {
        return tenant + ":" + (value == null ? "" : value.trim().toLowerCase());
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

    private static String tenant() {
        String tenant = com.socp.platform.tenant.TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
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
        return e;
    }

    static Ioc fromEntity(IocEntity e) {
        List<String> tags = readList(e.getTagsJson());
        return new Ioc(e.getId(), e.getType(), e.getValue(), e.getSeverity(), e.getSource(),
                e.getDescription(), tags == null ? List.of() : tags, e.getFirstSeen(), e.getLastSeen());
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
