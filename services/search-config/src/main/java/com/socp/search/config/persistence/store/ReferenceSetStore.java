package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ReferenceSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 查找表存储（集群无关内存实现）。生产由 CMDB/HR/威胁情报同步。 */
@Component
public class ReferenceSetStore {

    private final TenantCatalog<ReferenceSet> catalog;
    private boolean seeding = true;

    public ReferenceSetStore() {
        this(null, null);
    }

    @Autowired
    public ReferenceSetStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(ReferenceSet::id)
                : new TenantCatalog<>(ReferenceSet::id, "reference_set", ReferenceSet.class,
                persistence, objectMapper);
        seed();
        seeding = false;
    }

    private void seed() {
        add(ReferenceSet.of("核心资产(critical_assets)", "需重点保护的核心服务器/网段",
                List.of("web01", "db-prod", "10.0.0.1", "10.0.0.10")));
        add(ReferenceSet.of("关键人员(vip_users)", "高管/管理员账号",
                List.of("admin", "root", "ceo", "cfo")));
        add(ReferenceSet.of("封禁名单(blocked_ips)", "已确认恶意/失陷的 IP",
                List.of("10.0.0.66", "45.146.165.37", "185.220.101.1")));
        add(ReferenceSet.of("威胁组织(threat_actors)", "已知 APT/攻击组织",
                List.of("APT28", "Lazarus")));
    }

    public synchronized ReferenceSet add(ReferenceSet rs) {
        if (seeding) {
            catalog.registerTemplate(rs);
            return rs;
        }
        return catalog.save(rs);
    }

    public List<ReferenceSet> list() {
        return catalog.list();
    }

    public ReferenceSet get(String id) {
        return catalog.get(id);
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }

    /** 判断某值是否属于某查找表（大小写不敏感）。 */
    public boolean contains(String setName, String value) {
        if (value == null) return false;
        ReferenceSet rs = list().stream()
                .filter(item -> item.name().equalsIgnoreCase(setName))
                .findFirst().orElse(null);
        if (rs == null) return false;
        String v = value.toLowerCase(Locale.ROOT);
        return rs.entries().stream().anyMatch(e -> e.toLowerCase(Locale.ROOT).equals(v));
    }

    /** 返回某值命中的查找表名称列表（用于事件富化标注）。 */
    public List<String> matchedSets(String value) {
        if (value == null) return List.of();
        String v = value.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (ReferenceSet rs : list()) {
            if (rs.entries().stream().anyMatch(e -> e.toLowerCase(Locale.ROOT).equals(v))) {
                out.add(rs.name());
            }
        }
        return out;
    }
}
