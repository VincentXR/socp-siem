package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.Watchlists;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观察名单存储：运营侧维护的动态名单（离职人员、特权账号、暴露资产、封禁 IP…）。
 * 写入后同步刷进 {@link Watchlists} 全局注册表，规则条件 {@code op=inlist} 立即生效，
 * 无需修改规则、无需重启引擎——这是规则可运营性的核心。
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
        Watchlists.put(tenant(), name, values);
        return describe(name);
    }

    public Map<String, Object> append(String name, List<String> values) {
        Watchlists.add(tenant(), name, values);
        return describe(name);
    }

    public boolean delete(String name) {
        return Watchlists.delete(tenant(), name);
    }

    public List<Map<String, Object>> list() {
        return Watchlists.names(tenant()).stream().map(this::describe).toList();
    }

    public Map<String, Object> describe(String name) {
        Set<String> vals = new LinkedHashSet<>(Watchlists.values(tenant(), name));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("size", vals.size());
        m.put("values", vals);
        return m;
    }

    private static String tenant() {
        return TenantContext.require();
    }
}
