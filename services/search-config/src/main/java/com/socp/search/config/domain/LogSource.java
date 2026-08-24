package com.socp.search.config.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 日志源定义——一条接入配置。覆盖大厂 SIEM 接入的完整参数面：
 * 接入方式(type) + 网络参数(protocol/address) + 读取细节(readFrom/multiline/frequency)
 * + 编码时区(charset/timezone) + 语义标注(categoryId/tags/description)
 * + 解析标注(format/parseRuleIds) + 输出目标(sinkTargetId)。
 */
public record LogSource(
        String id,
        @NotBlank @Size(max = 128)
        String name,
        @NotNull
        SourceType type,
        @NotNull
        ParseFormat format,
        /** FILE 源的路径或 glob，如 /var/log/**\/*.log */
        @Size(max = 2048) String path,
        /** SOCKET/SYSLOG 的监听地址 host:port */
        @Size(max = 512) String address,
        /** KAFKA 主题名 */
        @Size(max = 512) String topic,
        /** 自定义环境标签，原样透传进事件字段，便于按环境过滤 */
        @Size(max = 2000) String env,
        boolean enabled,
        /** FILE 源：beginning=全量回放 / end=只收新日志 */
        @Size(max = 32) String readFrom,
        /** FILE 源：多行日志合并配置（Java 堆栈等），形如 {"start":"^\\S","condition":"^\\s","timeout_ms":1000} */
        @Size(max = 65536) String multiline,
        /** 输出目标 ID（SinkTarget），空则用默认 SEARCH ingest */
        @Size(max = 128) String sinkTargetId,
        /** 关联的解析规则 ID 列表（ParseRule），空则用 format 自动探测 */
        @Size(max = 100) List<@Size(max = 128) String> parseRuleIds,
        @Size(max = 2000) String description,
        /** SYSLOG/SOCKET 的传输协议：udp / tcp / tls */
        @Size(max = 16) String protocol,
        /** 字符集（默认 utf-8，GBK 等中文 Windows 日志常用） */
        @Size(max = 64) String charset,
        /** 事件时间字段（解析后用于时间索引，缺省 event_time） */
        @Size(max = 128) String timeField,
        /** 时区（如 Asia/Shanghai / UTC） */
        @Size(max = 64) String timezone,
        /** 语义标签（如 app=nginx, team=infra） */
        @Size(max = 100) List<@Size(max = 128) String> tags,
        /** FILE 轮询间隔（秒） */
        @Min(1) @Max(86400) Integer frequency,
        /** 日志类别 ID（元数据管理 LogCategory） */
        @Size(max = 128) String categoryId,
        /** KAFKA 消费组 */
        @Size(max = 128) String groupId,
        Instant createdAt
) {
    public static LogSource create(String name, SourceType type, ParseFormat format,
                                   String path, String address, String topic, String env, boolean enabled) {
        return createFull(name, type, format, path, address, topic, env, enabled,
                "beginning", null, null, List.of(), null,
                null, "utf-8", "event_time", null, List.of(), 1, null, null);
    }

    public static LogSource createFull(String name, SourceType type, ParseFormat format,
                                       String path, String address, String topic, String env, boolean enabled,
                                       String readFrom, String multiline, String sinkTargetId,
                                       List<String> parseRuleIds, String description,
                                       String protocol, String charset, String timeField, String timezone,
                                       List<String> tags, Integer frequency, String categoryId, String groupId) {
        return new LogSource(UUID.randomUUID().toString(), name, type, format,
                path, address, topic, env, enabled, readFrom, multiline, sinkTargetId,
                parseRuleIds == null ? List.of() : List.copyOf(parseRuleIds), description,
                protocol, charset, timeField, timezone,
                tags == null ? List.of() : List.copyOf(tags),
                frequency, categoryId, groupId, Instant.now());
    }

    /** 渲染用的归一化事件标签键：避免与正文解析出的 host 冲突（同 com.siem Vector 契约） */
    public String collectorTag() {
        return "search-" + (name == null ? id : name).toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
