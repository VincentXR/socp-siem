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

    public synchronized Ioc add(Ioc ioc) {
        repo.save(toEntity(ioc));
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
        repo.delete(entity.get());
        return true;
    }

    /** 精确匹配单个值（大小写不敏感），命中返回 IOC，否则 null。 */
    public Ioc match(String value) {
        if (value == null || value.isBlank()) return null;
        return repo.findByTenantIdAndValue(tenant(), value.trim().toLowerCase())
                .map(IocStore::fromEntity).orElse(null);
    }

    /** 批量匹配：给定若干候选值，返回 value -> IOC 的命中映射。 */
    public Map<String, Ioc> matchAll(List<String> values) {
        Map<String, Ioc> out = new LinkedHashMap<>();
        for (String val : values) {
            if (val == null) continue;
            Ioc hit = match(val);
            if (hit != null) out.put(val, hit);
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
