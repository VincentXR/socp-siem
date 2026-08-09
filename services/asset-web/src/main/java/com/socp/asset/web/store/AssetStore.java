package com.socp.asset.web.store;

import com.socp.asset.web.model.Asset;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资产存储——进程内；生产替换为 PG asset.t_asset，接口不变。
 */
@Component
public class AssetStore {

    private final ConcurrentHashMap<String, Asset> map = new ConcurrentHashMap<>();

    public AssetStore() {
        save(Asset.create("web01", "SERVER", "10.0.0.5", "Ubuntu 22.04", "infra", "HIGH"));
        save(Asset.create("web02", "SERVER", "10.0.0.6", "Ubuntu 22.04", "infra", "HIGH"));
        save(Asset.create("fw-core", "FIREWALL", "10.0.0.1", "pfSense", "sec", "CRITICAL"));
        save(Asset.create("db-master", "DATABASE", "10.0.0.10", "PostgreSQL 18", "dba", "CRITICAL"));
        save(Asset.create("kafka-1", "MESSAGE", "10.0.0.20", "Kafka 4.0", "infra", "HIGH"));
    }

    public List<Asset> list() {
        return map.values().stream().toList();
    }

    public Asset save(Asset a) {
        map.put(a.id(), a);
        return a;
    }

    /** 按 IP 去重：已存在同 IP 资产则更新（保留原 id），否则新建。 */
    public Asset upsertByIp(Asset a) {
        for (Asset existing : map.values()) {
            if (existing.ip().equals(a.ip()) && !a.ip().isBlank()) {
                Asset merged = new Asset(existing.id(), a.name(), a.type(), a.ip(), a.os(), a.owner(),
                        a.criticality(), existing.createdAt());
                map.put(existing.id(), merged);
                return merged;
            }
        }
        return save(a);
    }

    public boolean delete(String id) {
        return map.remove(id) != null;
    }
}
