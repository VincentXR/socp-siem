package com.socp.search.config.domain;

/**
 * 日志解析格式枚举。
 *
 * <p>直接迁移自 com.siem 的 {@code ParserChain.defaults()}（syslog/json/kv/cef/leef），
 * 作为 SEARCH 的「解析格式注册表」。AUTO 表示交给责任链依次尝试，与 com.siem 的兜底行为一致，
 * 保证管道不被脏数据打断。
 */
public enum ParseFormat {
    /** RFC3164/RFC5424 syslog */
    SYSLOG,
    /** 单行 JSON 事件 */
    JSON,
    /** key=value 扁平日志 */
    KV,
    /** CEF（ArcSight Common Event Format） */
    CEF,
    /** LEEF（Log Event Extended Format） */
    LEEF,
    /** 责任链自动探测：依次尝试 SYSLOG→JSON→KV→CEF→LEEF，全失败退化为 unknown */
    AUTO
}
