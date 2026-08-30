package com.socp.soc.persistence.store;


import com.socp.soc.persistence.repository.TenantRepository;
import com.socp.soc.persistence.entity.TenantEntity;
import com.socp.soc.domain.TenantInfo;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户存储——进程内；生产替换为 PG soc.t_tenant，接口不变。
 * 审计事件消费（Kafka socp-audit）在 SOC 这层统一落库，当前暂记为内存日志。
 */
@Component
public class TenantStore {

    private final TenantRepository repository;
    private final ConcurrentHashMap<String, TenantInfo> fallback = new ConcurrentHashMap<>();

    /**
     * Lightweight fallback retained for isolated unit tests and local callers
     * that construct the store outside Spring. Runtime wiring always chooses
     * the JPA constructor below.
     */
    public TenantStore() {
        this.repository = null;
        seedFallback();
    }

    @Autowired
    public TenantStore(TenantRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    void initializePersistentDirectory() {
        if (repository == null) return;
        ensureSeed("默认租户", "default");
        ensureSeed("安全运营团队", "soc-team");
        ensureSeed("基础设施组", "infra");
    }

    public List<TenantInfo> list() {
        if (repository == null) return fallback.values().stream().toList();
        return repository.findAllByOrderByCodeAsc().stream().map(TenantEntity::toInfo).toList();
    }

    public TenantInfo save(TenantInfo t) {
        if (repository == null) {
            fallback.put(t.id(), t);
            return t;
        }
        TenantEntity entity = repository.findById(t.id()).orElseGet(() -> new TenantEntity(t));
        entity.update(t);
        repository.save(entity);
        return t;
    }

    public TenantInfo get(String id) {
        if (repository == null) return fallback.get(id);
        return repository.findById(id).map(TenantEntity::toInfo).orElse(null);
    }

    private void seedFallback() {
        putFallback(TenantInfo.create("默认租户", "default"));
        putFallback(TenantInfo.create("安全运营团队", "soc-team"));
        putFallback(TenantInfo.create("基础设施组", "infra"));
    }

    private void putFallback(TenantInfo tenant) {
        fallback.put(tenant.id(), tenant);
    }

    private void ensureSeed(String name, String code) {
        if (repository.findByCode(code).isPresent()) return;
        try {
            save(TenantInfo.create(name, code));
        } catch (DataIntegrityViolationException racedInsert) {
            // Multiple service instances can initialize against the same
            // directory. The unique code key makes the loser harmless.
            if (repository.findByCode(code).isEmpty()) throw racedInsert;
        }
    }
}
