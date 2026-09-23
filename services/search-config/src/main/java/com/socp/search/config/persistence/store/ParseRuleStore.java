package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.ParseRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * 解析规则存储——租户覆盖项落 PG search.t_tenant_catalog_entry，内置规则为共享模板。
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class ParseRuleStore {

    private static final int MAX_RULES = 512;
    private static final int MAX_GLOBAL_RULES = 32;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private final TenantCatalog<ParseRule> catalog;
    private final TenantCatalogPersistence persistence;
    private final ObjectMapper objectMapper;
    private boolean seeding = true;
    private final TenantRevisionTracker revisions = new TenantRevisionTracker();

    public ParseRuleStore() {
        this(null, null);
    }

    @Autowired
    public ParseRuleStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.persistence = persistence;
        this.objectMapper = objectMapper == null ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.catalog = persistence == null
                ? new TenantCatalog<>(ParseRule::id)
                : new TenantCatalog<>(ParseRule::id, "parse_rule", ParseRule.class,
                persistence, this.objectMapper);
        seed();
        seeding = false;
    }

    private void seed() {
        // 示例 1：从 auth 日志行提取字段（正则命名分组）
        // 注意：本机 JDK 的正则命名分组不支持下划线（(?<src_ip>...) 编译报错），
        // 故组名用 srcip，再经 mapping 映射为事件字段 src_ip。
        save(ParseRule.createWithId(
                "sshd-auth-failed", "SSHD 认证失败提取", null,
                "REGEX",
                "Failed password for (?<user>\\S+) from (?<srcip>\\d+\\.\\d+\\.\\d+\\.\\d+)",
                List.of(new ParseRule.FieldMapping("user", "user", null),
                        new ParseRule.FieldMapping("srcip", "src_ip", null)),
                List.of(new ParseRule.FieldMapping("category", "auth_result", "failed")),
                true, 10));
        // 示例 2：Web 攻击行提取
        save(ParseRule.createWithId(
                "nginx-attack", "Nginx 请求提取", null,
                "REGEX",
                "(?<srcip>\\d+\\.\\d+\\.\\d+\\.\\d+) .*\"(?<method>GET|POST|PUT|DELETE) (?<uri>[^ ]+) HTTP",
                List.of(new ParseRule.FieldMapping("srcip", "src_ip", null),
                        new ParseRule.FieldMapping("method", "http_method", null),
                        new ParseRule.FieldMapping("uri", "url", null)),
                List.of(), true, 20));
    }

    public List<ParseRule> list() {
        return catalog.list().stream().sorted(java.util.Comparator.comparingInt(ParseRule::order)
                .thenComparing(ParseRule::id)).toList();
    }

    /** Stable page over the effective catalogue; historic over-limit rows may still be materialized. */
    public Page<ParseRule> page(String query, Pageable pageable) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<ParseRule> matching = list().stream()
                .filter(rule -> needle.isEmpty() || rule.name().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int from = (int) Math.min(pageable.getOffset(), matching.size());
        int until = Math.min(from + pageable.getPageSize(), matching.size());
        return new PageImpl<>(matching.subList(from, until), pageable, matching.size());
    }

    public List<ParseRule> enabled() {
        return list().stream().filter(ParseRule::enabled).toList();
    }

    public ParseRule save(ParseRule r) {
        if (seeding) {
            catalog.registerTemplate(r);
            return r;
        }
        return create(r);
    }

    public ParseRule create(ParseRule rule) {
        ParseRule saved = mutate(() -> {
            if (catalog.get(rule.id()) != null) throw new ApiException(409, "Parse rule ID already exists");
            List<ParseRule> current = catalog.list();
            if (current.size() >= MAX_RULES) {
                throw ApiException.badRequest("Tenant parse rule limit exceeded: " + MAX_RULES);
            }
            if (global(rule) && current.stream().filter(ParseRuleStore::global).count() >= MAX_GLOBAL_RULES) {
                throw ApiException.badRequest("Global parse rule limit exceeded: " + MAX_GLOBAL_RULES);
            }
            validatePayload(rule);
            return catalog.save(rule);
        });
        bumpRevision();
        return saved;
    }

    public ParseRule update(String id, UnaryOperator<ParseRule> edit) {
        ParseRule saved = mutate(() -> {
            ParseRule existing = catalog.get(id);
            if (existing == null) throw ApiException.notFound("Parse rule not found");
            ParseRule updated = edit.apply(existing);
            if (!Objects.equals(updated.id(), id)) throw new ApiException(409, "Parse rule ID cannot change");
            if (!global(existing) && global(updated)
                    && catalog.list().stream().filter(ParseRuleStore::global).count() >= MAX_GLOBAL_RULES) {
                throw ApiException.badRequest("Global parse rule limit exceeded: " + MAX_GLOBAL_RULES);
            }
            validatePayload(updated);
            return catalog.save(updated);
        });
        bumpRevision();
        return saved;
    }

    public ParseRule get(String id) {
        return catalog.get(id);
    }

    public List<ParseRule> getMany(List<String> ids) {
        return catalog.getMany(ids);
    }

    public boolean delete(String id) {
        boolean deleted = mutate(() -> catalog.delete(id));
        if (deleted) bumpRevision();
        return deleted;
    }

    private static boolean global(ParseRule rule) {
        return rule.sourceId() == null || rule.sourceId().isBlank();
    }

    private void validatePayload(ParseRule rule) {
        try {
            if (objectMapper.writeValueAsBytes(rule).length > MAX_PAYLOAD_BYTES) {
                throw ApiException.badRequest("Parse rule exceeds " + MAX_PAYLOAD_BYTES + " serialized bytes");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw ApiException.badRequest("Parse rule cannot be serialized");
        }
    }

    private <T> T mutate(Supplier<T> operation) {
        if (persistence != null) return persistence.mutate(operation);
        synchronized (catalog) { return operation.get(); }
    }

    /**
     * Change token of one tenant's rule set. Built-in templates are registered while the
     * store is being constructed — before any request is served — so they need no token.
     * Tokens are per tenant: another tenant's write must not invalidate this tenant's
     * compiled parser cache.
     */
    public long revision(String tenantId) {
        return revisions.revision(tenantId);
    }

    private void bumpRevision() {
        revisions.bump(TenantContext.require());
    }
}
