package com.socp.asset.web.persistence.store;


import com.socp.asset.web.persistence.repository.AssetRepository;
import com.socp.asset.web.persistence.entity.AssetEntity;
import com.socp.asset.web.domain.Asset;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 资产存储——JPA + H2 文件库（Flyway V1 建表），重启不丢；接口与原内存版一致。
 * 种子数据仅在空库时写入（避免重复）。
 */
@Component
public class AssetStore {

    private final AssetRepository repo;
    private final boolean demoDataEnabled;

    public AssetStore(AssetRepository repo) {
        this(repo, true);
    }

    @Autowired
    public AssetStore(AssetRepository repo,
                      @Value("${socp.demo-data.enabled:true}") boolean demoDataEnabled) {
        this.repo = repo;
        this.demoDataEnabled = demoDataEnabled;
        TenantContext.runWith("default", () -> {
            if (demoDataEnabled && repo.countByTenantId("default") == 0) {
                seed();
            }
        });
    }

    private String tenant() {
        return TenantContext.require();
    }

    private void seed() {
        save(Asset.create("web01", "SERVER", "10.0.0.5", "Ubuntu 22.04", "infra", "HIGH"));
        save(Asset.create("web02", "SERVER", "10.0.0.6", "Ubuntu 22.04", "infra", "HIGH"));
        save(Asset.create("fw-core", "FIREWALL", "10.0.0.1", "pfSense", "sec", "CRITICAL"));
        save(Asset.create("db-master", "DATABASE", "10.0.0.10", "PostgreSQL 18", "dba", "CRITICAL"));
        save(Asset.create("kafka-1", "MESSAGE", "10.0.0.20", "Kafka 4.0", "infra", "HIGH"));
    }

    public List<Asset> list() {
        return repo.findByTenantId(tenant()).stream().map(AssetStore::fromEntity).toList();
    }

    /** Reads one bounded tenant page directly from the database. */
    public Page<Asset> page(int page, int size, String query) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
        String normalized = query == null ? "" : query.trim();
        Page<AssetEntity> result = normalized.isEmpty()
                ? repo.findByTenantId(tenant(), pageable)
                : repo.searchByTenantId(tenant(), normalized, pageable);
        return result.map(AssetStore::fromEntity);
    }

    public long count() {
        return repo.countByTenantId(tenant());
    }

    /** Computes dimensions in the database instead of materializing the tenant inventory. */
    public Map<String, Object> stats() {
        String tenant = tenant();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", repo.countByTenantId(tenant));
        result.put("byType", aggregate(repo.countByTenantIdGroupByType(tenant)));
        result.put("byCriticality", aggregate(repo.countByTenantIdGroupByCriticality(tenant)));
        result.put("byOwner", aggregate(repo.countByTenantIdGroupByOwner(tenant)));
        return result;
    }

    public Asset save(Asset a) {
        AssetEntity e = toEntity(a, tenant());
        repo.save(e);
        return a;
    }

    /** 按 IP 去重：已存在同 IP 资产则更新（保留原 id），否则新建。 */
    public Asset upsertByIp(Asset a) {
        String t = tenant();
        List<AssetEntity> same = repo.findByIpAndTenantId(a.ip(), t);
        if (!a.ip().isBlank() && !same.isEmpty()) {
            AssetEntity existing = same.get(0);
            existing.setName(a.name());
            existing.setType(a.type());
            existing.setOs(a.os());
            existing.setOwner(a.owner());
            existing.setCriticality(a.criticality());
            repo.save(existing);
            return new Asset(existing.getId(), a.name(), a.type(), a.ip(), a.os(), a.owner(),
                    a.criticality(), existing.getCreatedAt());
        }
        return save(a);
    }

    public boolean delete(String id) {
        Optional<AssetEntity> e = repo.findByIdAndTenantId(id, tenant());
        if (e.isEmpty()) return false;
        repo.delete(e.get());
        return true;
    }

    public Asset get(String id) {
        return repo.findByIdAndTenantId(id, tenant()).map(AssetStore::fromEntity).orElse(null);
    }

    private static Asset fromEntity(AssetEntity e) {
        return new Asset(e.getId(), e.getName(), e.getType(), e.getIp(), e.getOs(), e.getOwner(),
                e.getCriticality(), e.getCreatedAt());
    }

    private static AssetEntity toEntity(Asset a, String tenant) {
        AssetEntity e = new AssetEntity();
        e.setId(a.id());
        e.setName(a.name());
        e.setType(a.type());
        e.setIp(a.ip());
        e.setOs(a.os());
        e.setOwner(a.owner());
        e.setCriticality(a.criticality());
        e.setCreatedAt(a.createdAt() == null ? Instant.now() : a.createdAt());
        e.setTenantId(tenant);
        return e;
    }

    private static Map<String, Long> aggregate(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (rows == null) return result;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || !(row[1] instanceof Number count)) continue;
            result.merge(bucket(row[0] == null ? null : String.valueOf(row[0])), count.longValue(), Long::sum);
        }
        return result;
    }

    private static String bucket(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }
}
