package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.util.Json;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则描述存储——JPA + H2 文件库（Flyway V1 建表），重启不丢；接口与原内存版一致。
 * 规则以 RuleSpec 的 JSON Map 形态保存（见 {@link com.socp.rule.config.RuleSpec}），spec 整体序列化为 JSON 列。
 */
@Component
public class RuleSpecStore {

    private final RuleRepository repo;
    private final Set<String> initializedTenants = ConcurrentHashMap.newKeySet();

    public RuleSpecStore(RuleRepository repo) {
        this.repo = repo;
        TenantContext.runWith("default", () -> ensureTenantContent("default"));
    }

    /**
     * Install new packaged rules and upgrade rules that are still owned by the
     * packaged content set. User-created rules, including a colliding id that
     * has no contentPack marker, remain untouched.
     */
    private void syncPackagedContent(String tenant) {
        Map<String, Object> manifest = DetectionContentCatalog.manifest();
        String packId = String.valueOf(manifest.get("packId"));
        String packVersion = String.valueOf(manifest.get("version"));
        Object rawRules = manifest.get("rules");
        if (!(rawRules instanceof List<?> rules)) return;

        for (Object item : rules) {
            if (!(item instanceof Map<?, ?> map) || !(map.get("spec") instanceof Map<?, ?> rawSpec)) continue;
            Map<String, Object> spec = new LinkedHashMap<>();
            rawSpec.forEach((key, value) -> spec.put(String.valueOf(key), value));
            String id = String.valueOf(spec.getOrDefault("id", ""));
            if (id.isBlank()) continue;
            Optional<RuleEntity> current = repo.findByRuleIdAndTenantId(id, tenant);
            if (current.isEmpty()) {
                try {
                    save(spec, tenant);
                } catch (DataIntegrityViolationException racedInstaller) {
                    // Multiple Detection instances can start against the same
                    // database. Another instance winning this idempotent insert
                    // race means the packaged rule is already installed.
                    if (repo.findByRuleIdAndTenantId(id, tenant).isEmpty()) throw racedInstaller;
                }
                continue;
            }
            Map<String, Object> stored = Json.parseObject(current.get().getSpec());
            boolean packageOwned = packId.equals(String.valueOf(stored.get("contentPack")));
            boolean currentVersion = packVersion.equals(String.valueOf(stored.get("contentVersion")));
            if (packageOwned && !currentVersion) save(spec, tenant);
        }
    }

    public String tenant() {
        return TenantContext.require();
    }

    public boolean isEmpty() {
        return repo.countByTenantId(tenant()) == 0;
    }

    public Map<String, Object> save(Map<String, Object> spec) {
        return save(spec, tenant());
    }

    public Map<String, Object> save(Map<String, Object> spec, String tenant) {
        spec = DetectionContentCatalog.enrich(spec);
        // The current workbench still sends the legacy enabled toggle. Keep it
        // compatible with the lifecycle status while preserving explicit
        // TESTING/DRAFT/ARCHIVED states owned by detection engineering.
        if (spec.containsKey("enabled") && spec.get("status") != null) {
            String status = String.valueOf(spec.get("status")).toUpperCase();
            if ("ACTIVE".equals(status) || "DISABLED".equals(status)) {
                spec.put("status", Boolean.parseBoolean(String.valueOf(spec.get("enabled")))
                        ? "ACTIVE" : "DISABLED");
            }
        }
        Object id = spec.get("id");
        if (id == null || String.valueOf(id).isBlank()) {
            // 前端新建规则可不带 id，服务端生成
            spec = new LinkedHashMap<>(spec);
            spec.put("id", "RULE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        List<String> errors = DetectionContentCatalog.validateSpec(spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("规则内容校验失败: " + String.join(", ", errors));
        }
        String ruleId = String.valueOf(spec.get("id"));
        RuleEntity e = repo.findByRuleIdAndTenantId(ruleId, tenant).orElseGet(RuleEntity::new);
        e.setId(String.valueOf(spec.get("id")));
        if (e.getStorageId() == null) e.setStorageId(storageId(tenant, ruleId));
        try {
            e.setSpec(Json.mapper().writeValueAsString(spec));
        } catch (Exception ex) {
            throw new IllegalStateException("规则 JSON 序列化失败: " + ex.getMessage(), ex);
        }
        e.setTenantId(tenant);
        repo.save(e);
        return spec;
    }

    public List<Map<String, Object>> list() {
        return list(tenant());
    }

    public List<Map<String, Object>> list(String tenant) {
        ensureTenantContent(tenant);
        return repo.findByTenantId(tenant).stream()
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())))
                .toList();
    }

    public Map<String, Object> get(String id) {
        return get(id, tenant());
    }

    public Map<String, Object> get(String id, String tenant) {
        ensureTenantContent(tenant);
        return repo.findByRuleIdAndTenantId(id, tenant)
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())))
                .orElse(null);
    }

    public Map<String, Object> contentManifest() {
        return DetectionContentCatalog.manifest();
    }

    public boolean delete(String id) {
        String tenant = tenant();
        Optional<RuleEntity> e = repo.findByRuleIdAndTenantId(id, tenant);
        if (e.isEmpty()) return false;
        repo.delete(e.get());
        return true;
    }

    private static String storageId(String tenant, String ruleId) {
        return UUID.nameUUIDFromBytes((tenant + "|" + ruleId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private void ensureTenantContent(String tenant) {
        String normalized = tenant == null || tenant.isBlank() ? "default" : tenant;
        if (!initializedTenants.add(normalized)) return;
        try {
            syncPackagedContent(normalized);
        } catch (RuntimeException failure) {
            initializedTenants.remove(normalized);
            throw failure;
        }
    }
}
