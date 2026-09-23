package com.socp.detect.web.persistence.store;


import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.engine.WatchlistLimits;
import com.socp.platform.error.exception.ApiException;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观察名单存储：运营侧维护的动态名单（离职人员、特权账号、暴露资产、封禁 IP…）。
 * 写入事务提交后失效本机缓存，其他实例在缓存刷新时读取持久化值。
 * 规则条件 {@code op=inlist} 无需修改规则或重启引擎。
 */
@Component
public class WatchlistStore {

    private final PersistentWatchlistStateStore persistentState;

    /** 内置示例名单，让功能开箱即用 */
    private static final Map<String, List<String>> SEED = Map.of(
            "privileged_accounts", List.of("root", "administrator", "admin", "dbadmin", "svc_backup"),
            "terminated_staff", List.of("zhangsan", "lisi_leaver"),
            "crown_jewels", List.of("10.0.0.10", "10.0.0.11", "db-core-01", "erp-prod-01"),
            "blocked_ips", List.of("203.0.113.66", "198.51.100.23"),
            "high_risk_geo", List.of("kp", "unknown", "tor-exit")
    );

    public WatchlistStore(PersistentWatchlistStateStore persistentState) {
        this.persistentState = persistentState;
    }

    @PostConstruct
    public void init() {
        Watchlists.installStateStore(persistentState);
        SEED.forEach(Watchlists::putTemplate);
    }

    public Map<String, Object> put(String name, List<String> values) {
        return describe(name, Watchlists.put(tenant(), name, values));
    }

    public Map<String, Object> create(String name, List<String> values) {
        try {
            return describe(name, Watchlists.create(tenant(), name, values));
        } catch (Watchlists.AlreadyExistsException conflict) {
            throw ApiException.of(409, "watchlist already exists");
        }
    }

    public Map<String, Object> append(String name, List<String> values) {
        return describe(name, Watchlists.add(tenant(), name, values));
    }

    public boolean delete(String name) {
        return Watchlists.delete(tenant(), name);
    }

    public List<Map<String, Object>> list() {
        return list(true);
    }

    public List<Map<String, Object>> list(boolean includeValues) {
        Map<String, Integer> sizes = new java.util.TreeMap<>();
        SEED.forEach((name, values) -> sizes.put(name, values.size()));
        for (var summary : persistentState.summaries(tenant())) {
            if (summary.getDeleted()) sizes.remove(summary.getName());
            else sizes.put(summary.getName(), summary.getSize());
        }
        if (!includeValues) return sizes.entrySet().stream()
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "size", entry.getValue())).toList();
        if (sizes.values().stream().mapToLong(Integer::longValue).sum() > WatchlistLimits.MAX_VALUES) {
            throw ApiException.of(413, "use includeValues=false and load individual watchlists; catalogue member limit exceeded");
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        long members = 0;
        for (String name : sizes.keySet()) {
            var description = describe(name);
            members += ((Number) description.get("size")).longValue();
            if (members > WatchlistLimits.MAX_VALUES) {
                throw ApiException.of(413, "use includeValues=false and load individual watchlists; catalogue member limit exceeded");
            }
            result.add(description);
        }
        return result;
    }

    public Map<String, Object> describe(String name) {
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        WatchlistLimits.name(normalized);
        var state = persistentState.findFresh(tenant(), normalized);
        Set<String> vals = state == null ? new LinkedHashSet<>(SEED.getOrDefault(normalized, List.of()))
                : state.deleted() ? Set.of() : state.values();
        WatchlistLimits.values(vals);
        return describe(name, vals);
    }

    private Map<String, Object> describe(String name, Set<String> vals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name.trim().toLowerCase(java.util.Locale.ROOT));
        m.put("size", vals.size());
        m.put("values", new java.util.TreeSet<>(vals));
        return m;
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
