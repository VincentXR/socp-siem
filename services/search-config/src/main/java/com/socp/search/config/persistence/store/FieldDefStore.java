package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.FieldDef;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Canonical field metadata: packaged templates and durable tenant overlays. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class FieldDefStore {

    private final TenantCatalog<FieldDef> catalog;
    private final MetadataCatalogPolicy<FieldDef> metadata;
    private boolean seeding = true;

    public FieldDefStore() {
        this(null, null);
    }

    @Autowired
    public FieldDefStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(FieldDef::id)
                : new TenantCatalog<>(FieldDef::id, "field_def", FieldDef.class,
                persistence, objectMapper);
        metadata = new MetadataCatalogPolicy<>(catalog, persistence, FieldDef::id, FieldDef::fieldName,
                "BUILTIN-FIELD-", 1024, false);
        seed();
        seeding = false;
    }

    private void seed() {
        // 系统内置字段
        seedItem(FieldDef.create("timestamp", "事件时间", "date", "system", true, false, true, "事件发生时间（解析自日志或采集时刻）"));
        seedItem(FieldDef.create("source", "日志来源", "string", "system", true, true, true, "来源通道/解析器标识"));
        seedItem(FieldDef.create("host", "主机", "string", "system", true, true, true, "事件产生主机（采集时转 collector_host）"));
        seedItem(FieldDef.create("collector_host", "采集主机", "string", "system", true, true, true, "Vector 采集节点标识，多机部署自动区分"));
        seedItem(FieldDef.create("collector_tag", "采集标签", "string", "system", true, true, false, "日志源渲染标签，ingest 侧反查日志源配置"));
        seedItem(FieldDef.create("msg", "原始消息", "string", "system", true, false, true, "日志正文"));
        seedItem(FieldDef.create("severity", "严重级别", "string", "system", true, true, true, "INFO/LOW/MEDIUM/HIGH/CRITICAL"));
        seedItem(FieldDef.create("raw", "原始行", "string", "system", false, false, true, "未解析的原始日志行"));
        // 解析产生字段
        seedItem(FieldDef.create("src_ip", "源 IP", "ip", "parse", true, true, true, "发起方 IP（解析自日志或网络层）"));
        seedItem(FieldDef.create("dst_ip", "目的 IP", "ip", "parse", true, true, true, "目标 IP"));
        seedItem(FieldDef.create("user", "用户", "string", "parse", true, true, true, "账号/用户标识"));
        seedItem(FieldDef.create("action", "动作", "string", "parse", true, true, false, "allow/deny/block 等处置动作"));
        seedItem(FieldDef.create("url", "URL", "string", "parse", true, false, true, "请求 URL"));
        seedItem(FieldDef.create("http_method", "HTTP 方法", "string", "parse", true, true, true, "GET/POST/PUT/DELETE"));
        seedItem(FieldDef.create("bytes", "字节数", "long", "parse", true, true, false, "传输字节数（数值比较规则用）"));
        seedItem(FieldDef.create("category", "日志类别", "string", "parse", true, true, true, "AUTH/NETWORK/WEB 等分类码"));
    }

    private void seedItem(FieldDef value) {
        save(new FieldDef("BUILTIN-FIELD-" + value.fieldName(), value.fieldName(), value.fieldLabel(),
                value.fieldType(), value.source(), value.searchable(), value.aggregatable(), value.stored(),
                value.description(), java.time.Instant.EPOCH));
    }

    public List<FieldDef> list() {
        return metadata.list();
    }

    public FieldDef get(String id) {
        return catalog.get(id);
    }

    public FieldDef save(FieldDef f) {
        if (seeding) {
            catalog.registerTemplate(f);
            return f;
        }
        validateInput(f);
        return metadata.save(f);
    }

    private static void validateInput(FieldDef field) {
        if (field.source() == null || field.fieldType() == null
                || !java.util.Set.of("parse", "custom").contains(field.source())
                || !java.util.Set.of("string", "int", "long", "float", "ip", "date", "bool", "json")
                        .contains(field.fieldType())) {
            throw com.socp.platform.error.exception.ApiException.badRequest("Unsupported field source or type");
        }
    }

    public FieldDef update(String id, FieldDef requested) {
        return metadata.update(id, existing -> {
            if ("system".equals(existing.source()) || "system".equals(requested.source())
                    || !java.util.Objects.equals(existing.fieldName(), requested.fieldName())
                    || !java.util.Objects.equals(existing.fieldType(), requested.fieldType())) {
                throw new com.socp.platform.error.exception.ApiException(409,
                        "System fields and existing field identifiers or types cannot be changed");
            }
            validateInput(requested);
            return new FieldDef(id, existing.fieldName(), requested.fieldLabel(), existing.fieldType(),
                    requested.source(), requested.searchable(), requested.aggregatable(), requested.stored(),
                    requested.description(), existing.createdAt());
        });
    }

    public boolean delete(String id) {
        return metadata.delete(id, item -> {
            if ("system".equals(item.source())) {
                throw new com.socp.platform.error.exception.ApiException(409, "System fields are read-only");
            }
        });
    }
}
