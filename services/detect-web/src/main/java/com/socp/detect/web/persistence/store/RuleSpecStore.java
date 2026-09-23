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
import com.socp.detect.web.model.RuleCatalogMetadata;
import com.socp.detect.web.model.RuleWriteCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 规则描述存储——JPA + PostgreSQL/H2，按租户串行化内容安装和规则修订。
 * 规则以 RuleSpec 的 JSON Map 形态保存（见 {@link com.socp.rule.config.RuleSpec}），spec 整体序列化为 JSON 列。
 */
@Component
public class RuleSpecStore {

    private static final Logger LOG = LoggerFactory.getLogger(RuleSpecStore.class);

    /** Compatibility/list endpoints must never materialise an unbounded tenant catalogue. */
    private static final int MAX_COMPATIBILITY_LIST_SIZE = 500;
    private static final int MAX_HISTORY_PAGE_SIZE = 100;
    private static final RuleCatalogCoordinator.Pack PACK = contentPack();

    private final RuleRepository repo;
    private final RuleRevisionRepository revisions;
    private final RuleContentConflictRepository conflicts;
    private final RuleCatalogCoordinator catalog;
    private final com.socp.detect.web.routing.DetectionRoutingTopologyGuard topologyGuard;

    public RuleSpecStore(RuleRepository repo, RuleRevisionRepository revisions,
                         RuleContentConflictRepository conflicts, RuleCatalogCoordinator catalog) {
        this(repo, revisions, conflicts, catalog, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuleSpecStore(RuleRepository repo, RuleRevisionRepository revisions,
                         RuleContentConflictRepository conflicts, RuleCatalogCoordinator catalog,
                         com.socp.detect.web.routing.DetectionRoutingTopologyGuard topologyGuard) {
        this.topologyGuard = topologyGuard;
        this.repo = repo;
        this.revisions = revisions;
        this.conflicts = conflicts;
        this.catalog = catalog;
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
                if (revisions.findFirstByTenantIdAndRuleIdOrderByRevisionDesc(tenant, id)
                        .map(head -> "DELETE".equals(head.getSource())).orElse(false)) continue;
                savePackaged(spec, tenant);
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
        return inCatalog(tenant, () -> saveInternal(spec, tenant, false, false));
    }

    /** API creation is never an upsert, including Sigma imports with supplied IDs. */
    public Map<String, Object> create(Map<String, Object> spec) {
        String tenant = tenant();
        return inCatalog(tenant, () -> {
            Object id = spec.get("id");
            if (id != null && repo.findByRuleIdAndTenantId(String.valueOf(id), tenant).isPresent()) {
                throw ApiException.of(409, "rule already exists; read it and use a conditional update");
            }
            return saveInternal(spec, tenant, false, false);
        });
    }

    public Map<String, Object> update(Map<String, Object> spec, RuleWriteCondition condition) {
        String tenant = tenant();
        return inCatalog(tenant, () -> {
            Map<String, Object> current = current(String.valueOf(spec.get("id")), tenant);
            if (condition != null) condition.check(current);
            if (current == null) throw ApiException.notFound("rule not found");
            Map<String, Object> updated = new LinkedHashMap<>(spec);
            String status = String.valueOf(updated.getOrDefault("status", current.get("status"))).toUpperCase(java.util.Locale.ROOT);
            boolean wasActive = "ACTIVE".equalsIgnoreCase(String.valueOf(current.get("status")));
            if ("ACTIVE".equals(status)) {
                if (!wasActive) throw ApiException.forbidden("rule activation requires the /activate transition");
                if (Boolean.FALSE.equals(updated.get("enabled"))) status = "DISABLED";
            } else if ("DISABLED".equals(status) && Boolean.TRUE.equals(updated.get("enabled"))) {
                throw ApiException.forbidden("rule activation requires the /activate transition");
            }
            updated.put("status", status);
            updated.put("enabled", "ACTIVE".equals(status));
            return saveInternal(updated, tenant, false, false);
        });
    }

    public Map<String, Object> activate(String id, RuleWriteCondition condition) {
        String tenant = tenant();
        return inCatalog(tenant, () -> {
            Map<String, Object> current = current(id, tenant);
            if (condition != null) condition.check(current);
            if (current == null) throw ApiException.notFound("rule not found");
            if ("ARCHIVED".equalsIgnoreCase(String.valueOf(current.get("status")))) throw ApiException.of(409, "archived rule cannot be activated");
            current.put("status", "ACTIVE");
            current.put("enabled", true);
            return saveInternal(current, tenant, false, false);
        });
    }

    private Map<String, Object> current(String id, String tenant) {
        return repo.findByRuleIdAndTenantId(id, tenant).map(this::representation).orElse(null);
    }

    private Map<String, Object> representation(RuleEntity entity) {
        return RuleWriteCondition.representation(entity.getTenantId(), DetectionContentCatalog.enrich(Json.parseObject(entity.getSpec())));
    }

    private Map<String, Object> savePackaged(Map<String, Object> spec, String tenant) {
        return saveInternal(spec, tenant, true, false);
    }

    private Map<String, Object> saveInternal(Map<String, Object> input, String tenant,
                                             boolean packagedWrite, boolean restore) {
        return saveChecked(input, tenant, packagedWrite, restore);
    }

    private Map<String, Object> saveChecked(Map<String, Object> input, String tenant,
                                            boolean packagedWrite, boolean restore) {
        Map<String, Object> spec = DetectionContentCatalog.enrich(input);
        Object requestedId = spec.get("id");
        RuleEntity existing = requestedId == null || String.valueOf(requestedId).isBlank()
                ? null : repo.findByRuleIdAndTenantId(String.valueOf(requestedId), tenant).orElse(null);
        if (packagedWrite) {
            spec.put("contentPack", PACK.id());
            spec.put("contentVersion", PACK.version());
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
            spec.put("id", "RULE-" + UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
        }
        List<String> errors = DetectionContentCatalog.validateSpec(spec);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("rule contract validation failed: " + String.join(", ", errors));
        }
        compileOrReject(spec);
        // Caller metadata is never a version authority. A fresh persisted nonce
        // prevents stale writes after restore or delete/recreate, even for equal bodies.
        spec.put(RuleWriteCondition.TOKEN, UUID.randomUUID().toString());
        String ruleId = String.valueOf(spec.get("id"));
        if (topologyGuard != null) topologyGuard.validateMutation(tenant, ruleId, spec);
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
        return RuleWriteCondition.representation(tenant, spec);
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
        conflicts.save(conflict);
    }

    /** Legacy full-spec response, oldest first; large histories require paged metadata. */
    public List<Map<String, Object>> revisions(String ruleId) {
        return revisions(ruleId, tenant());
    }

    public List<Map<String, Object>> revisions(String ruleId, String tenant) {
        ensureTenantContent(tenant);
        var result = revisions.findByTenantIdAndRuleIdOrderByRevisionAsc(tenant, ruleId,
                PageRequest.of(0, MAX_HISTORY_PAGE_SIZE));
        if (result.hasNext()) throw ApiException.badRequest("rule history exceeds 100 revisions; use page and size, then fetch one revision");
        return result.getContent().stream().map(RuleSpecStore::revisionDetail).toList();
    }

    public Page<Map<String, Object>> revisionPage(String ruleId, int page, int size) {
        checkHistoryPage(page, size);
        String tenant = tenant();
        ensureTenantContent(tenant);
        return revisions.findAllByTenantIdAndRuleId(tenant, ruleId,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "revision")))
                .map(row -> revisionMetadata(row.getRevision(), row.getRuleId(), row.getStatus(),
                        row.getSource(), row.getChangedBy(), row.getChangedAt()));
    }

    public Map<String, Object> revision(String ruleId, long revision) {
        if (revision < 1) throw ApiException.badRequest("revision must be positive");
        String tenant = tenant();
        ensureTenantContent(tenant);
        return revisions.findByTenantIdAndRuleIdAndRevision(tenant, ruleId, revision)
                .map(RuleSpecStore::revisionDetail).orElse(null);
    }

    private static Map<String, Object> revisionMetadata(long revision, String ruleId, String status,
                                                       String source, String changedBy, Instant changedAt) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("revision", revision);
        entry.put("ruleId", ruleId);
        entry.put("status", status);
        entry.put("source", source);
        entry.put("changedBy", changedBy);
        entry.put("changedAt", changedAt == null ? null : changedAt.toString());
        return entry;
    }

    private static Map<String, Object> revisionDetail(RuleRevisionEntity row) {
        Map<String, Object> entry = revisionMetadata(row.getRevision(), row.getRuleId(), row.getStatus(),
                row.getSource(), row.getChangedBy(), row.getChangedAt());
        Map<String, Object> spec = Json.parseObject(row.getSpec());
        entry.put("spec", spec.containsKey(RuleWriteCondition.TOKEN)
                ? RuleWriteCondition.representation(row.getTenantId(), spec) : spec);
        return entry;
    }

    /** Pending "content pack updated, local customized" conflicts for the tenant. */
    public List<Map<String, Object>> contentConflicts() {
        var result = contentConflictPage(1, MAX_HISTORY_PAGE_SIZE);
        if (result.hasNext()) throw ApiException.badRequest("content conflicts exceed 100 records; use page and size");
        return result.getContent();
    }

    public Page<Map<String, Object>> contentConflictPage(int page, int size) {
        checkHistoryPage(page, size);
        String tenant = tenant();
        ensureTenantContent(tenant);
        return conflicts.findByTenantIdAndStatus(tenant, "PENDING",
                PageRequest.of(page - 1, size, Sort.by("detectedAt", "id"))).map(conflict -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ruleId", conflict.getRuleId());
            entry.put("contentPack", conflict.getContentPack());
            entry.put("packVersion", conflict.getPackVersion());
            entry.put("storedVersion", conflict.getStoredVersion());
            entry.put("status", conflict.getStatus());
            entry.put("detectedAt", conflict.getDetectedAt() == null ? null : conflict.getDetectedAt().toString());
            return entry;
        });
    }

    private static void checkHistoryPage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_HISTORY_PAGE_SIZE) {
            throw ApiException.badRequest("page must be >= 1 and size between 1 and 100");
        }
    }

    /**
     * Re-applies a historical revision as the rule's new head, appending a
     * RESTORE entry rather than deleting history. Returns the persisted spec, or
     * null when the revision does not exist for this tenant. A deleted rule can
     * be recreated from its retained revision.
     */
    public Map<String, Object> restoreRevision(String ruleId, long revision) {
        return restoreRevision(ruleId, revision, null);
    }

    public Map<String, Object> restoreRevision(String ruleId, long revision, RuleWriteCondition condition) {
        String tenant = tenant();
        return inCatalog(tenant, () -> {
            if (condition != null) condition.check(current(ruleId, tenant));
            RuleRevisionEntity target = revisions
                    .findByTenantIdAndRuleIdAndRevision(tenant, ruleId, revision).orElse(null);
            if (target == null) return null;
            Map<String, Object> spec = Json.parseObject(target.getSpec());
            return saveInternal(spec, tenant, false, true);
        });
    }

    public List<Map<String, Object>> list() {
        return list(tenant());
    }

    public List<Map<String, Object>> list(String tenant) {
        ensureTenantContent(tenant);
        return repo.findByTenantId(tenant).stream()
                .map(this::representation)
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
                        Sort.by(Sort.Order.asc("ruleId"))))
                .map(this::representation)
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
                        Sort.by(Sort.Order.asc("ruleId"))))
                .map(this::representation);
    }

    public Page<Map<String, Object>> search(int page, int size, String keyword, String status,
                                            String reference, String alias) {
        String tenant = tenant();
        ensureTenantContent(tenant);
        return repo.search(tenant, keyword.toLowerCase(java.util.Locale.ROOT), status,
                        reference.isEmpty() ? "" : RuleCatalogMetadata.referenceToken(reference),
                        alias.isEmpty() ? "" : RuleCatalogMetadata.referenceToken(alias),
                        PageRequest.of(page - 1, size, Sort.by("ruleId")))
                .map(this::representation);
    }

    public Page<Map<String, Object>> options(int page, int size, String keyword) {
        String tenant = tenant();
        ensureTenantContent(tenant);
        return repo.options(tenant, keyword.toLowerCase(java.util.Locale.ROOT), PageRequest.of(page - 1, size));
    }

    public List<Map<String, Object>> lookup(List<String> ids) {
        String tenant = tenant();
        ensureTenantContent(tenant);
        return ids.isEmpty() ? List.of() : repo.lookup(tenant, ids);
    }

    public List<String> activeTechniques() {
        String tenant = tenant();
        ensureTenantContent(tenant);
        // Return a bounded projection, never complete rule bodies. Oversized
        // metadata fails explicitly instead of silently reporting partial coverage.
        List<String> groups = repo.activeTechniques(tenant, PageRequest.of(0, 10_001));
        if (groups.size() > 10_000) throw ApiException.badRequest("active technique catalogue exceeds 10000 groups");
        Set<String> techniques = new java.util.TreeSet<>();
        for (String group : groups) {
            techniques.addAll(List.of(group.split("\n")));
            if (techniques.size() > 10_000) throw ApiException.badRequest("active technique catalogue exceeds 10000 techniques");
        }
        return List.copyOf(techniques);
    }

    public Map<String, Object> get(String id) {
        return get(id, tenant());
    }

    public Map<String, Object> get(String id, String tenant) {
        ensureTenantContent(tenant);
        return repo.findByRuleIdAndTenantId(id, tenant)
                .map(this::representation)
                .orElse(null);
    }

    public Map<String, Object> contentManifest() {
        return DetectionContentCatalog.manifest();
    }

    public boolean delete(String id) {
        return delete(id, null);
    }

    public boolean delete(String id, RuleWriteCondition condition) {
        String tenant = tenant();
        return inCatalog(tenant, () -> {
            Optional<RuleEntity> e = repo.findByRuleIdAndTenantId(id, tenant);
            Map<String, Object> current = e.map(this::representation).orElse(null);
            if (condition != null) {
                condition.check(current);
                if (current != null && "ACTIVE".equalsIgnoreCase(String.valueOf(current.get("status")))) {
                    throw ApiException.of(409, "disable the rule before deleting it");
                }
            }
            if (e.isEmpty()) return false;
            if (topologyGuard != null) topologyGuard.validateMutation(tenant, id, null);
            appendRevision(tenant, id, e.get().getSpec(),
                    Json.parseObject(e.get().getSpec()).get("status"), "DELETE");
            repo.delete(e.get());
            return true;
        });
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
        inCatalog(tenant, () -> null);
    }

    private <T> T inCatalog(String tenant, Supplier<T> operation) {
        Supplier<T> coordinated = () -> catalog.withCatalog(tenant, PACK,
                () -> syncPackagedContent(tenant), operation);
        // First pin also holds topology before reading the catalogue. Keep one lock order.
        return topologyGuard == null ? coordinated.get() : topologyGuard.mutate(tenant, coordinated);
    }

    private static RuleCatalogCoordinator.Pack contentPack() {
        Map<String, Object> manifest = DetectionContentCatalog.manifest();
        try {
            byte[] canonical = Json.mapper().writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(manifest);
            String fingerprint = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
            return new RuleCatalogCoordinator.Pack(String.valueOf(manifest.get("packId")),
                    String.valueOf(manifest.get("version")), fingerprint);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to fingerprint packaged rule content", failure);
        }
    }
}
