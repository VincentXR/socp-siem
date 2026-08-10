package com.socp.search.config.parser;

import java.util.Map;

/**
 * 日志解析器接口：把一条原始日志行解析成 canonical 字段（键为 ECS 风格 {@code namespace.field}）。
 *
 * <p>约定：
 * <ul>
 *   <li>返回 {@code null} 表示「本解析器不适用该行」（由 SourceRouter 决定是否继续尝试下一个）；</li>
 *   <li>解析失败（格式是这种、但内容坏）抛 {@link IllegalArgumentException}，由调用方计入 parse failure；</li>
 *   <li>输出键尽量走 {@link CanonicalEvent} 常量；厂商专有字段保留原键名。</li>
 * </ul>
 */
public interface EventParser {

    /** 解析器名称（日志与监控 tag 用），如 "json" / "syslog" / "cef"。 */
    String name();

    /** 尝试解析一行；不适用返回 null，格式坏抛异常。 */
    Map<String, String> parse(String raw);
}
