package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.LogCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Log category metadata: packaged templates and durable tenant overlays. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class LogCategoryStore {

    private final TenantCatalog<LogCategory> catalog;
    private final MetadataCatalogPolicy<LogCategory> metadata;
    private boolean seeding = true;

    public LogCategoryStore() {
        this(null, null);
    }

    @Autowired
    public LogCategoryStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistence == null
                ? new TenantCatalog<>(LogCategory::id)
                : new TenantCatalog<>(LogCategory::id, "log_category", LogCategory.class,
                persistence, objectMapper);
        metadata = new MetadataCatalogPolicy<>(catalog, persistence, LogCategory::id, LogCategory::code,
                "BUILTIN-CATEGORY-", 128, true);
        seed();
        seeding = false;
    }

    private void seed() {
        seedItem(LogCategory.create("AUTH", "认证与访问", "登录、权限、账号操作（暴力破解/提权检测域）", "HIGH", true));
        seedItem(LogCategory.create("NETWORK", "网络与防火墙", "防火墙、IDS/IPS、路由交换设备流量与拦截", "MEDIUM", true));
        seedItem(LogCategory.create("WEB", "Web 应用", "Web 服务器/应用/WAF 请求与攻击", "HIGH", true));
        seedItem(LogCategory.create("ENDPOINT", "端点与终端", "主机进程、文件、注册表、HIPS/Falco 事件", "HIGH", true));
        seedItem(LogCategory.create("DATABASE", "数据库", "DB 审计、慢查询、权限变更", "MEDIUM", true));
        seedItem(LogCategory.create("APPLICATION", "业务应用", "业务系统、中间件（Nginx/Redis/消息队列）", "LOW", true));
        seedItem(LogCategory.create("THREAT_INTEL", "威胁情报", "情报源 IOC 匹配、恶意域名/IP/哈希", "CRITICAL", true));
        seedItem(LogCategory.create("COMPLIANCE", "合规审计", "等保/SOX 审计、操作留痕、管理审计", "MEDIUM", true));
    }

    private void seedItem(LogCategory value) {
        save(new LogCategory("BUILTIN-CATEGORY-" + value.code(), value.code(), value.name(),
                value.description(), value.defaultSeverity(), value.enabled(), java.time.Instant.EPOCH));
    }

    public List<LogCategory> list() {
        return metadata.list();
    }

    public LogCategory get(String id) {
        return catalog.get(id);
    }

    public LogCategory save(LogCategory c) {
        if (seeding) {
            catalog.registerTemplate(c);
            return c;
        }
        return metadata.save(c);
    }

    public LogCategory update(String id, LogCategory requested) {
        return metadata.update(id, existing -> {
            if (!java.util.Objects.equals(existing.code(), requested.code())) {
                throw new com.socp.platform.error.exception.ApiException(409, "Log category code cannot be changed");
            }
            return new LogCategory(id, existing.code(), requested.name(), requested.description(),
                    requested.defaultSeverity(), requested.enabled(), existing.createdAt());
        });
    }

    public boolean delete(String id) {
        return metadata.delete(id, item -> { });
    }
}
