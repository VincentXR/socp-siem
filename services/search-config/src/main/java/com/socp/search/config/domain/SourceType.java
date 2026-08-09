package com.socp.search.config.domain;

/**
 * 采集源类型（接入方式分类）。对齐大厂 SIEM 的接入矩阵：
 * 网络协议类（syslog/socket）、消息队列类（Kafka）、文件类、端点类（Agent/Windows 事件）、
 * 平台类（API/HTTP、数据库、云日志）。
 *
 * <p>Vector 原生支持 FILE/SOCKET/SYSLOG/KAFKA 的渲染；其余类型由对应采集器
 * （Agent/Winlogbeat/Beats/云 SDK）负责，渲染器输出配置注释与对接说明。
 */
public enum SourceType {
    /** 文件尾部监听（等价 Vector file source） */
    FILE,
    /** 裸 TCP/UDP socket（等价 Vector socket source，保留完整原始行） */
    SOCKET,
    /** syslog（等价 Vector 的 syslog source，标准 UDP/TCP/TLS 514） */
    SYSLOG,
    /** Kafka 主题消费 */
    KAFKA,
    /** Windows 事件日志（EventLog/ETW，Winlogbeat 等采集器负责） */
    WINDOWS_EVENT,
    /** 端点 Agent 上报（HIPS/Falco Agent 通过 gRPC/WebSocket 推送） */
    AGENT,
    /** HTTP/API 主动推送（webhook、SIEM API 上传） */
    HTTP_API,
    /** 数据库日志采集（DB 日志表/CDC） */
    DATABASE,
    /** 云平台日志（AWS CloudTrail / 腾讯云 CLS 等，云 SDK 拉取） */
    CLOUD
}
