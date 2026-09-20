package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.detect.web.persistence.entity.RuleContentConflictEntity;
import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.detect.web.persistence.entity.RuleRevisionEntity;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.rules.Rule;
import com.socp.rule.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
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

    private static final Logger LOG = LoggerFactory.getLogger(RuleSpecStore.class);

    /** Compatibility/list endpoints must never materialise an unbounded tenant catalogue. */
    private static final int MAX_COMPATIBILITY_LIST_SIZE = 500;

    private final RuleRepository repo;
    private final RuleRevisionRepository revisions;
    private final RuleContentConflictRepository conflicts;
    private final Set<String> initializedTenants = ConcurrentHashMap.newKeySet();

    public RuleSpecStore(RuleRepository repo, RuleRevisionRepository revisions,
                         RuleContentConflictRepository conflicts) {
        this.repo = repo;
        this.revisions = revisions;
        this.conflicts = conflicts;
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
                    savePackaged(spec, tenant);
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
            boolean customized = Boolean.TRUE.equals(stored.get("contentCustomized"));
            boolean currentVersion = packVersion.equals(String.valueOf(stored.get("contentVersion")));
            if (packageOwned && !customized && !currentVersion) {
                savePackaged(spec, tenant);
            } else if (packageOwned && customized && !currentVersion) {
                // The analyst customized this rule and the running content pack
                // advertises a newer version. Never overwrite the local tuning;
                // record the pending upgrade so it surfaces instead of silently
                // diverging from the pack.
                recordContentConflict(tenant, id, packId, packVersion,
                        String.valueOf(stored.get("contentVersion")));
            }
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
        return saveInternal(spec, tenant, false, false);
    }

    private Map<String, Object> savePackaged(Map<String, Object> spec, String tenant) {
        return saveInternal(spec, tenant, true, false);
    }

    private Map<String, Object> saveInternal(Map<String, Object> input, String tenant,
                                             boolean packagedWrite, boolean restore) {
        Map<String, Object> spec = DetectionContentCatalog.enrich(input);
        Object requestedId = spec.get("id");
        RuleEntity existing = requestedId == null || String.valueOf(requestedId).isBlank()
                ? null : repo.findByRuleIdAndTenantId(String.valueOf(requestedId), tenant).orElse(null);
        if (packagedWrite) {
            spec.remove("contentCustomized");
        } else if (existing == null) {
            // A user-created rule is user-owned even when its id collides with a
            // packaged rule id. Catalog enrichment supplies display defaults by
            // id, but ownership must come from the write path, not from the id.
            spec.remove("contentPack");
            spec.remove("contentVersion");
            spec.put("contentCustomized", true);
        } else {
            Map<String, Object> stored = Json.parseObject(existing.getSpec());
            Object originalPack = stored.get("contentPack");
            if (originalPack != null && !String.valueOf(originalPack).isBlank()) {
                spec.put("contentPack", originalPack);
                if (stored.get("contentVersion") != null) {
                    spec.put("contentVersion", stored.get("contentVersion"));
                }
                spec.put("contentCustomized", true);
            } else {
                spec.remove("contentPack");
                spec.remove("contentVersion");
                spec.put("contentCustomized", true);
            }
        }
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
            throw ApiException.badRequest("rule contract validation failed: " + String.join(", ", errors));
        }
        compileOrReject(spec);
        String ruleId = String.valueOf(spec.get("id"));
        for (String advisory : DetectionContentCatalog.partitionLocalAdvisories(spec)) {
            // Deliberately not a rejection: the packaged content set contains
            // rules whose grouping dimension can lose to a higher-priority
            // routing field. The event path counts what actually happens.
            LOG.warn("Rule {} partition-locality advisory: {}", ruleId, advisory);
        }
        RuleEntity e = existing == null ? new RuleEntity() : existing;
        e.setId(String.valueOf(spec.get("id")));
        if (e.getStorageId() == null) e.setStorageId(storageId(tenant, ruleId));
        String specJson;
        try {
            specJson = Json.mapper().writeValueAsString(spec);
            e.setSpec(specJson);
        } catch (Exception ex) {
            throw new IllegalStateException("规则 JSON 序列化失败: " + ex.getMessage(), ex);
        }
        e.setTenantId(tenant);
        repo.save(e);
        if (!packagedWrite) {
            // User-facing mutations carry the durable version chain. Packaged
            // installs are excluded: their source of truth is the manifest and
            // recording every replica install would only add churn.
            String source = restore ? "RESTORE" : (existing == null ? "ADD" : "EDIT");
            appendRevision(tenant, ruleId, specJson, spec.get("status"), source);
        }
        return spec;
    }

    /**
     * Appends one immutable row to the rule's version chain, in the caller's
     * transaction. Every user mutation (add/edit/activate/restore/delete) writes
     * the full spec plus the acting identity, so the chain is diffable and a
     * rollback reuses a prior spec instead of an unrecoverable overwrite.
     */
    private void appendRevision(String tenant, String ruleId, String specJson,
                                Object status, String source) {
        if (tenant == null || ruleId == null || specJson == null) return;
        RuleRevisionEntity revision = new RuleRevisionEntity();
        revision.setId(UUID.randomUUID().toString());
        revision.setTenantId(tenant);
        revision.setRuleId(ruleId);
        revision.setRevision(revisions.maxRevision(tenant, ruleId) + 1);
        revision.setSpec(specJson);
        revision.setStatus(truncateStatus(status));
        revision.setSource(source);
        revision.setChangedBy(actor());
        revision.setChangedAt(Instant.now());
        revisions.save(revision);
    }

    private void recordContentConflict(String tenant, String ruleId, String packId,
                                       String packVersion, String storedVersion) {
        String stored = storedVersion == null || "null".equals(storedVersion) ? null : storedVersion;
        Optional<RuleContentConflictEntity> existing = conflicts
                .findByTenantIdAndRuleIdAndContentPackAndPackVersion(tenant, ruleId, packId, packVersion);
        if (existing.isPresent()) {
            RuleContentConflictEntity conflict = existing.get();
            // Reopen a conflict that was resolved but whose local copy is again
            // behind the running pack version.
            if ("RESOLVED".equals(conflict.getStatus())) {
                conflict.setStatus("PENDING");
                conflict.setStoredVersion(stored);
                conflict.setDetectedAt(Instant.now());
                conflict.setResolvedAt(null);
                conflicts.save(conflict);
            }
            return;
        }
        RuleContentConflictEntity conflict = new RuleContentConflictEntity();
        conflict.setId(UUID.randomUUID().toString());
        conflict.setTenantId(tenant);
        conflict.setRuleId(ruleId);
        conflict.setContentPack(packId);
        conflict.setPackVersion(packVersion);
        conflict.setStoredVersion(stored);
        conflict.setStatus("PENDING");
        conflict.setDetectedAt(Instant.now());
        try {
            conflicts.save(conflict);
        } catch (DataIntegrityViolationException racedRecorder) {
            // Concurrent replicas both detected the same upgrade; the unique key
            // keeps a single pending conflict per (rule, pack, version).
        }
    }

    /** Bounded, tenant-scoped view of a rule's version chain (oldest first). */
    public List<Map<String, Object>> revisions(String ruleId) {
        return revisions(ruleId, tenant());
    }

    public List<Map<String, Object>> revisions(String ruleId, String tenant) {
        ensureTenantContent(tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleRevisionEntity revision : revisions.findByTenantIdAndRuleIdOrderByRevisionAsc(tenant, ruleId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("revision", revision.getRevision());
            entry.put("ruleId", revision.getRuleId());
            entry.put("status", revision.getStatus());
            entry.put("source", revision.getSource());
            entry.put("changedBy", revision.getChangedBy());
            entry.put("changedAt", revision.getChangedAt() == null ? null : revision.getChangedAt().toString());
            entry.put("spec", Json.parseObject(revision.getSpec()));
            out.add(entry);
        }
        return out;
    }

    /** Pending "content pack updated, local customized" conflicts for the tenant. */
    public List<Map<String, Object>> contentConflicts() {
        String tenant = tenant();
        ensureTenantContent(tenant);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RuleContentConflictEntity conflict : conflicts
                .findByTenantIdAndStatusOrderByDetectedAtAsc(tenant, "PENDING")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleId", conflict.getRuleId());
            entry.put("contentPack", conflict.getContentPack());
            entry.put("packVersion", conflict.getPackVersion());
            entry.put("storedVersion", conflict.getStoredVersion());
            entry.put("status", conflict.getStatus());
            entry.put("detectedAt", conflict.getDetectedAt() == null ? null : conflict.getDetectedAt().toString());
            out.add(entry);
        }
        return out;
    }

    /**
     * Re-applies a historical revision as the rule's new head, appending a
     * RESTORE entry rather than deleting history. Returns the persisted spec, or
     * null when the rule or revision does not exist for this tenant.
     */
    public Map<String, Object> restoreRevision(String ruleId, long revision) {
        String tenant = tenant();
        ensureTenantContent(tenant);
        RuleRevisionEntity target = revisions
                .findByTenantIdAndRuleIdAndRevision(tenant, ruleId, revision).orElse(null);
        if (target == null) return null;
        Map<String, Object> spec = Json.parseObject(target.getSpec());
        return saveInternal(spec, tenant, false, true);
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

    /**
     * Reads only the first bounded page for legacy callers that do not need a
     * total count. The worker still uses {@link #list(String)} when it builds a
     * live engine because every enabled rule must be loaded into that engine.
     */
    public List<Map<String, Object>> list(int limit) {
        return list(tenant(), limit);
    }

    public List<Map<String, Object>> list(String tenant, int limit) {
        ensureTenantContent(tenant);
        int boundedLimit = Math.max(1, Math.min(MAX_COMPATIBILITY_LIST_SIZE, limit));
        return repo.findByTenantId(tenant, PageRequest.of(0, boundedLimit,
                        Sort.by(Sort.Order.asc("id"))))
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())))
                .getContent();
    }

    /** Database count used by runtime statistics and reload responses. */
    public long count() {
        return count(tenant());
    }

    public long count(String tenant) {
        ensureTenantContent(tenant);
        return repo.countByTenantId(tenant);
    }

    /** Reads a bounded rule page without materialising the tenant catalogue. */
    public Page<Map<String, Object>> page(int page, int size) {
        String tenant = tenant();
        ensureTenantContent(tenant);
        return repo.findByTenantId(tenant, PageRequest.of(page - 1, size,
                        Sort.by(Sort.Order.asc("id"))))
                .map(e -> DetectionContentCatalog.enrich(Json.parseObject(e.getSpec())));
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
        appendRevision(tenant, id, e.get().getSpec(),
                Json.parseObject(e.get().getSpec()).get("status"), "DELETE");
        repo.delete(e.get());
        return true;
    }

    /** Acting principal for the version chain; falls back to "system" outside a request. */
    private static String actor() {
        return AuthenticatedIdentityContext.current()
                .map(identity -> truncateActor(identity.subject()))
                .orElse("system");
    }

    private static String truncateActor(String value) {
        if (value == null || value.isBlank()) return "system";
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static String truncateStatus(Object status) {
        if (status == null) return null;
        String value = String.valueOf(status);
        return value.length() <= 32 ? value : value.substring(0, 32);
    }

    private static String storageId(String tenant, String ruleId) {
        return UUID.nameUUIDFromBytes((tenant + "|" + ruleId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Build the candidate with the exact code the engine uses, then release it.
     * Contract validation and rule construction previously accepted different
     * grammars, so a document could be stored and only fail when the engine was
     * assembled - which took the whole tenant's detection down. A rule the
     * engine cannot build is now a client error at write time.
     */
    private static void compileOrReject(Map<String, Object> spec) {
        try {
            Rule compiled = new RuleSpec(spec).toRule();
            compiled.close();
        } catch (RuntimeException failure) {
            throw ApiException.badRequest("rule cannot be compiled: " + describe(failure));
        }
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : failure.getClass().getSimpleName() + ": " + message;
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
