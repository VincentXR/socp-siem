package com.socp.soc.store;

import com.socp.soc.model.TenantInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户存储——进程内；生产替换为 PG soc.t_tenant，接口不变。
 * 审计事件消费（Kafka socp-audit）在 SOC 这层统一落库，当前暂记为内存日志。
 */
@Component
public class TenantStore {

    private final ConcurrentHashMap<String, TenantInfo> map = new ConcurrentHashMap<>();

    public TenantStore() {
        save(TenantInfo.create("默认租户", "default"));
        save(TenantInfo.create("安全运营团队", "soc-team"));
        save(TenantInfo.create("基础设施组", "infra"));
    }

    public List<TenantInfo> list() {
        return map.values().stream().toList();
    }

    public TenantInfo save(TenantInfo t) {
        map.put(t.id(), t);
        return t;
    }

    public TenantInfo get(String id) {
        return map.get(id);
    }
}
