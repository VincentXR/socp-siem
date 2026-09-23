package com.socp.search.config.persistence.store;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.DataSourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Data source metadata: packaged templates and durable tenant overlays. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
public class DataSourceTypeStore {

    private final TenantCatalog<DataSourceType> catalog;
    private final MetadataCatalogPolicy<DataSourceType> metadata;
    private boolean seeding = true;

    public DataSourceTypeStore() {
        this(null, null);
    }

    @Autowired
    public DataSourceTypeStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistentCatalog(persistence, objectMapper);
        metadata = new MetadataCatalogPolicy<>(catalog, persistence, DataSourceType::id, DataSourceType::code,
                "BUILTIN-TYPE-", 128, true);
        seed();
        seeding = false;
    }

    private static TenantCatalog<DataSourceType> persistentCatalog(
            TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        return persistence == null
                ? new TenantCatalog<>(DataSourceType::id)
                : new TenantCatalog<>(DataSourceType::id, "data_source_type", DataSourceType.class,
                persistence, objectMapper);
    }

    private void seed() {
        seedItem(DataSourceType.create("SYSLOG", "Syslog 协议", "标准 UDP/TCP 514 或自定义端口，网络设备/主机日志最常用", true));
        seedItem(DataSourceType.create("KAFKA", "Kafka 消息队列", "从消息队列消费日志，高吞吐接入（SOCP 生产主通道）", true));
        seedItem(DataSourceType.create("FILE", "文件采集", "监听本地/共享日志文件，支持多行合并与全量回放", true));
        seedItem(DataSourceType.create("SOCKET", "原始 TCP/UDP", "裸协议监听，保留完整原始行，适合自定义格式", true));
        seedItem(DataSourceType.create("WINDOWS_EVENT", "Windows 事件日志", "EventLog/ETW 通道，Winlogbeat 等采集器上报", true));
        seedItem(DataSourceType.create("AGENT", "端点 Agent", "HIPS/Falco Agent gRPC/WebSocket 推送运行时事件", true));
        seedItem(DataSourceType.create("HTTP_API", "HTTP/API 推送", "Webhook、SIEM API 上传、第三方平台对接", true));
        seedItem(DataSourceType.create("DATABASE", "数据库日志", "DB 日志表/CDC 变更流采集（Oracle/MySQL/PG）", false));
        seedItem(DataSourceType.create("CLOUD", "云平台日志", "AWS CloudTrail / 腾讯云 CLS / 阿里云 SLS 等", false));
    }

    private void seedItem(DataSourceType value) {
        save(new DataSourceType("BUILTIN-TYPE-" + value.code(), value.code(), value.name(),
                value.description(), value.enabled(), java.time.Instant.EPOCH));
    }

    public List<DataSourceType> list() {
        return metadata.list();
    }

    public DataSourceType get(String id) {
        return catalog.get(id);
    }

    public DataSourceType save(DataSourceType t) {
        if (seeding) {
            catalog.registerTemplate(t);
            return t;
        }
        return metadata.save(t);
    }

    public DataSourceType update(String id, DataSourceType requested) {
        return metadata.update(id, existing -> {
            if (!java.util.Objects.equals(existing.code(), requested.code())) {
                throw new com.socp.platform.error.exception.ApiException(409, "Data source type code cannot be changed");
            }
            return new DataSourceType(id, existing.code(), requested.name(), requested.description(),
                    requested.enabled(), existing.createdAt());
        });
    }

    public boolean delete(String id) {
        return metadata.delete(id, item -> { });
    }
}
