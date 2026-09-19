package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.ReferenceSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 查找表存储——租户覆盖项落 PG search.t_tenant_catalog_entry，内置集合为共享模板。 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class ReferenceSetStore {

    /** 与 ReferenceSetCreateRequest 的条目上限一致，堵住 addEntry 只校验单条的旁路。 */
    public static final int MAX_ENTRIES_PER_SET = 10_000;
    /** 与 ReferenceSetCreateRequest/ReferenceEntryRequest 的单值上限一致。 */
    public static final int MAX_ENTRY_VALUE_CHARS = 256;
    /** 租户查找表个数上限：归一化热路径的目录成本按「表数 × 条目数」增长。 */
    public static final int MAX_SETS_PER_TENANT = 200;

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
        requireWithinBounds(rs);
        return catalog.save(rs);
    }

    /**
     * Enrichment reads go through one snapshot per ingest request: the tenant catalogue is
     * read (and deserialised) once instead of once per event field. Freshness across SEARCH
     * instances is unchanged because the snapshot is still taken inside the request.
     */
    public Snapshot snapshot() {
        return new Snapshot(list());
    }

    public List<ReferenceSet> list() {
        return catalog.list();
    }

    public ReferenceSet get(String id) {
        return catalog.get(id);
    }

    public synchronized ReferenceSet removeEntry(String id, String value) {
        ReferenceSet existing = get(id);
        if (existing == null) return null;
        return add(new ReferenceSet(existing.id(), existing.name(), existing.description(),
                existing.entries().stream().filter(entry -> !entry.equals(value)).toList()));
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }

    /** 判断某值是否属于某查找表（大小写不敏感）。 */
    public boolean contains(String setName, String value) {
        return snapshot().contains(setName, value);
    }

    /** 返回某值命中的查找表名称列表（用于事件富化标注）。 */
    public List<String> matchedSets(String value) {
        return snapshot().matchedSets(value);
    }

    private void requireWithinBounds(ReferenceSet candidate) {
        List<String> entries = candidate.entries() == null ? List.of() : candidate.entries();
        if (entries.size() > MAX_ENTRIES_PER_SET) {
            throw ApiException.badRequest("查找表 " + candidate.name() + " 的条目数超过上限 "
                    + MAX_ENTRIES_PER_SET + "，请拆分为多张表");
        }
        for (String entry : entries) {
            if (entry == null || entry.length() > MAX_ENTRY_VALUE_CHARS) {
                throw ApiException.badRequest("查找表 " + candidate.name()
                        + " 含超过 " + MAX_ENTRY_VALUE_CHARS + " 字符的条目，已拒绝写入");
            }
        }
        if (get(candidate.id()) == null && list().size() >= MAX_SETS_PER_TENANT) {
            throw ApiException.badRequest("当前租户查找表数量已达上限 " + MAX_SETS_PER_TENANT
                    + "，请先删除不再使用的查找表");
        }
    }

    /** Immutable, pre-indexed view of one tenant's lookup tables. */
    public static final class Snapshot {

        private final Map<String, List<String>> setsByValue;
        private final Map<String, Map<String, Boolean>> valuesBySetName;

        Snapshot(List<ReferenceSet> sets) {
            Map<String, java.util.LinkedHashSet<String>> values = new HashMap<>();
            Map<String, Map<String, Boolean>> byName = new HashMap<>();
            for (ReferenceSet set : sets) {
                if (set.entries() == null || set.name() == null) continue;
                Map<String, Boolean> index = byName.computeIfAbsent(set.name().toLowerCase(Locale.ROOT),
                        ignored -> new HashMap<>());
                for (String entry : set.entries()) {
                    if (entry == null) continue;
                    String key = entry.toLowerCase(Locale.ROOT);
                    // One hit per table, as the previous per-set anyMatch scan produced.
                    values.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>())
                            .add(set.name());
                    index.put(key, Boolean.TRUE);
                }
            }
            Map<String, List<String>> indexed = new HashMap<>();
            values.forEach((key, names) -> indexed.put(key, List.copyOf(names)));
            this.setsByValue = Map.copyOf(indexed);
            this.valuesBySetName = Map.copyOf(byName);
        }

        /** An empty view: enrichment stays inert when a caller has no lookup tables. */
        public static final Snapshot EMPTY = new Snapshot(List.of());

        /** Set names matching one enrichment value (case-insensitive). */
        public List<String> matchedSets(String value) {
            if (value == null) return List.of();
            return setsByValue.getOrDefault(value.toLowerCase(Locale.ROOT), List.of());
        }

        /** True when {@code value} belongs to the named lookup table (both case-insensitive). */
        public boolean contains(String setName, String value) {
            if (setName == null || value == null) return false;
            Map<String, Boolean> index = valuesBySetName.get(setName.toLowerCase(Locale.ROOT));
            return index != null && index.containsKey(value.toLowerCase(Locale.ROOT));
        }
    }
}
