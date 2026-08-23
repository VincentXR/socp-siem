package com.socp.search.config.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.domain.DataSourceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据源分类存储（元数据）。进程内；生产替换为 PG search.t_data_source_type。
 */
@Component
public class DataSourceTypeStore {

    private final TenantCatalog<DataSourceType> catalog;
    private boolean seeding = true;

    public DataSourceTypeStore() {
        this(null, null);
    }

    @Autowired
    public DataSourceTypeStore(TenantCatalogPersistence persistence, ObjectMapper objectMapper) {
        this.catalog = persistentCatalog(persistence, objectMapper);
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
        save(DataSourceType.create("SYSLOG", "Syslog 协议", "标准 UDP/TCP 514 或自定义端口，网络设备/主机日志最常用", true));
        save(DataSourceType.create("KAFKA", "Kafka 消息队列", "从消息队列消费日志，高吞吐接入（SOCP 生产主通道）", true));
        save(DataSourceType.create("FILE", "文件采集", "监听本地/共享日志文件，支持多行合并与全量回放", true));
        save(DataSourceType.create("SOCKET", "原始 TCP/UDP", "裸协议监听，保留完整原始行，适合自定义格式", true));
        save(DataSourceType.create("WINDOWS_EVENT", "Windows 事件日志", "EventLog/ETW 通道，Winlogbeat 等采集器上报", true));
        save(DataSourceType.create("AGENT", "端点 Agent", "HIPS/Falco Agent gRPC/WebSocket 推送运行时事件", true));
        save(DataSourceType.create("HTTP_API", "HTTP/API 推送", "Webhook、SIEM API 上传、第三方平台对接", true));
        save(DataSourceType.create("DATABASE", "数据库日志", "DB 日志表/CDC 变更流采集（Oracle/MySQL/PG）", false));
        save(DataSourceType.create("CLOUD", "云平台日志", "AWS CloudTrail / 腾讯云 CLS / 阿里云 SLS 等", false));
    }

    public List<DataSourceType> list() {
        return catalog.list();
    }

    public DataSourceType save(DataSourceType t) {
        if (seeding) {
            catalog.registerTemplate(t);
            return t;
        }
        return catalog.save(t);
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }
}
