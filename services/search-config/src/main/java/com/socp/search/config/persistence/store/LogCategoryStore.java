package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.LogCategory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日志类别存储（元数据）。进程内；生产替换为 PG search.t_log_category。
 */
@Component
public class LogCategoryStore {

    private final TenantCatalog<LogCategory> catalog;
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
        seed();
        seeding = false;
    }

    private void seed() {
        save(LogCategory.create("AUTH", "认证与访问", "登录、权限、账号操作（暴力破解/提权检测域）", "HIGH", true));
        save(LogCategory.create("NETWORK", "网络与防火墙", "防火墙、IDS/IPS、路由交换设备流量与拦截", "MEDIUM", true));
        save(LogCategory.create("WEB", "Web 应用", "Web 服务器/应用/WAF 请求与攻击", "HIGH", true));
        save(LogCategory.create("ENDPOINT", "端点与终端", "主机进程、文件、注册表、HIPS/Falco 事件", "HIGH", true));
        save(LogCategory.create("DATABASE", "数据库", "DB 审计、慢查询、权限变更", "MEDIUM", true));
        save(LogCategory.create("APPLICATION", "业务应用", "业务系统、中间件（Nginx/Redis/消息队列）", "LOW", true));
        save(LogCategory.create("THREAT_INTEL", "威胁情报", "情报源 IOC 匹配、恶意域名/IP/哈希", "CRITICAL", true));
        save(LogCategory.create("COMPLIANCE", "合规审计", "等保/SOX 审计、操作留痕、管理审计", "MEDIUM", true));
    }

    public List<LogCategory> list() {
        return catalog.list();
    }

    public LogCategory save(LogCategory c) {
        if (seeding) {
            catalog.registerTemplate(c);
            return c;
        }
        return catalog.save(c);
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }
}
