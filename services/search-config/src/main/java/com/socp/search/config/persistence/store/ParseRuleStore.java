package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.ParseRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 解析规则存储——进程内；生产替换为 PG search.t_parse_rule，接口不变。
 */
@Component
public class ParseRuleStore {

    private final TenantCatalog<ParseRule> catalog;
    private boolean seeding = true;
    private final AtomicLong revision = new AtomicLong();

    public ParseRuleStore() {
        this(null, null);
    }

    @Autowired
    public ParseRuleStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(ParseRule::id)
                : new TenantCatalog<>(ParseRule::id, "parse_rule", ParseRule.class,
                persistence, objectMapper);
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
        return catalog.list().stream().sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
    }

    public List<ParseRule> enabled() {
        return list().stream().filter(ParseRule::enabled).toList();
    }

    public ParseRule save(ParseRule r) {
        if (seeding) {
            catalog.registerTemplate(r);
            revision.incrementAndGet();
            return r;
        }
        ParseRule saved = catalog.save(r);
        revision.incrementAndGet();
        return saved;
    }

    public ParseRule get(String id) {
        return catalog.get(id);
    }

    public boolean delete(String id) {
        boolean deleted = catalog.delete(id);
        if (deleted) revision.incrementAndGet();
        return deleted;
    }

    /** Cheap in-memory change token used by the compiled parser-pipeline cache. */
    public long revision() {
        return revision.get();
    }
}
