package com.socp.search.config.store;

import com.socp.search.config.domain.FieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字段字典存储（元数据）。进程内；生产替换为 PG search.t_field_dict。
 * 种子为平台内置字段（与 com.siem 归一化事件对齐）。
 */
@Component
public class FieldDefStore {

    private final TenantCatalog<FieldDef> catalog = new TenantCatalog<>(FieldDef::id);
    private boolean seeding = true;

    public FieldDefStore() {
        seed();
        seeding = false;
    }

    private void seed() {
        // 系统内置字段
        save(FieldDef.create("timestamp", "事件时间", "date", "system", true, false, true, "事件发生时间（解析自日志或采集时刻）"));
        save(FieldDef.create("source", "日志来源", "string", "system", true, true, true, "来源通道/解析器标识"));
        save(FieldDef.create("host", "主机", "string", "system", true, true, true, "事件产生主机（采集时转 collector_host）"));
        save(FieldDef.create("collector_host", "采集主机", "string", "system", true, true, true, "Vector 采集节点标识，多机部署自动区分"));
        save(FieldDef.create("collector_tag", "采集标签", "string", "system", true, true, false, "日志源渲染标签，ingest 侧反查日志源配置"));
        save(FieldDef.create("msg", "原始消息", "string", "system", true, false, true, "日志正文"));
        save(FieldDef.create("severity", "严重级别", "string", "system", true, true, true, "INFO/LOW/MEDIUM/HIGH/CRITICAL"));
        save(FieldDef.create("raw", "原始行", "string", "system", false, false, true, "未解析的原始日志行"));
        // 解析产生字段
        save(FieldDef.create("src_ip", "源 IP", "ip", "parse", true, true, true, "发起方 IP（解析自日志或网络层）"));
        save(FieldDef.create("dst_ip", "目的 IP", "ip", "parse", true, true, true, "目标 IP"));
        save(FieldDef.create("user", "用户", "string", "parse", true, true, true, "账号/用户标识"));
        save(FieldDef.create("action", "动作", "string", "parse", true, true, false, "allow/deny/block 等处置动作"));
        save(FieldDef.create("url", "URL", "string", "parse", true, false, true, "请求 URL"));
        save(FieldDef.create("http_method", "HTTP 方法", "string", "parse", true, true, true, "GET/POST/PUT/DELETE"));
        save(FieldDef.create("bytes", "字节数", "long", "parse", true, true, false, "传输字节数（数值比较规则用）"));
        save(FieldDef.create("category", "日志类别", "string", "parse", true, true, true, "AUTH/NETWORK/WEB 等分类码"));
    }

    public List<FieldDef> list() {
        return catalog.list();
    }

    public FieldDef save(FieldDef f) {
        if (seeding) {
            catalog.registerTemplate(f);
            return f;
        }
        return catalog.save(f);
    }

    public boolean delete(String id) {
        return catalog.delete(id);
    }
}
