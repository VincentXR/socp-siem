package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical Event Schema（2026-08-11，简化 ECS 风格）。
 *
 * <p>所有 parser 输出统一的字段模型（键为 {@code namespace.field}），
 * Detection Rule 用这些键写 match（如 {@code field: "event.category"}），
 * 不关心厂商/设备——Sysmon / Falco / auditd / CEF firewall 都归一到这里。
 *
 * <p>设计取舍：用 {@code Map<String,String>} 而非强类型 record——
 * parser 输出自由字段、检测规则按需取键，Map 最贴合规则引擎的 match 语义。
 */
public final class CanonicalEvent {

    // ---- event ----
    public static final String EVENT_CODE = "event.code";
    public static final String EVENT_CATEGORY = "event.category";
    public static final String EVENT_TYPE = "event.type";
    public static final String EVENT_ACTION = "event.action";
    public static final String EVENT_SEVERITY = "event.severity";
    public static final String EVENT_MESSAGE = "event.message";
    // ---- source / destination ----
    public static final String SOURCE_IP = "source.ip";
    public static final String SOURCE_PORT = "source.port";
    public static final String DESTINATION_IP = "destination.ip";
    public static final String DESTINATION_PORT = "destination.port";
    // ---- host / user ----
    public static final String HOST_NAME = "host.name";
    public static final String USER_NAME = "user.name";
    // ---- process ----
    public static final String PROCESS_NAME = "process.name";
    public static final String PROCESS_PID = "process.pid";
    public static final String PROCESS_COMMAND_LINE = "process.command_line";
    // ---- file ----
    public static final String FILE_PATH = "file.path";
    public static final String FILE_HASH_SHA256 = "file.hash.sha256";
    // ---- network ----
    public static final String NETWORK_PROTOCOL = "network.protocol";

    private CanonicalEvent() {
    }

    /** 宽松取值：null/空串视为缺失。 */
    public static String get(Map<String, String> m, String key) {
        String v = m.get(key);
        return (v == null || v.isBlank()) ? null : v;
    }

    public static void putIf(Map<String, String> out, String key, Object value) {
        if (value == null) return;
        String s = String.valueOf(value);
        if (!s.isBlank() && !"null".equalsIgnoreCase(s)) {
            out.put(key, s);
        }
    }

    /** 常见别名 → canonical 键（JsonParser 等用）。 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("src_ip", SOURCE_IP), Map.entry("source_ip", SOURCE_IP), Map.entry("srcip", SOURCE_IP),
            Map.entry("src_port", SOURCE_PORT), Map.entry("source_port", SOURCE_PORT),
            Map.entry("dst_ip", DESTINATION_IP), Map.entry("dest_ip", DESTINATION_IP),
            Map.entry("dstip", DESTINATION_IP), Map.entry("destination", DESTINATION_IP),
            Map.entry("dst_port", DESTINATION_PORT), Map.entry("dest_port", DESTINATION_PORT),
            Map.entry("host", HOST_NAME), Map.entry("hostname", HOST_NAME),
            Map.entry("user", USER_NAME), Map.entry("username", USER_NAME), Map.entry("user_name", USER_NAME),
            Map.entry("process", PROCESS_NAME), Map.entry("process_name", PROCESS_NAME),
            Map.entry("pid", PROCESS_PID),
            Map.entry("cmdline", PROCESS_COMMAND_LINE), Map.entry("command_line", PROCESS_COMMAND_LINE),
            Map.entry("cmd", PROCESS_COMMAND_LINE),
            Map.entry("proto", NETWORK_PROTOCOL), Map.entry("protocol", NETWORK_PROTOCOL),
            Map.entry("severity", EVENT_SEVERITY), Map.entry("msg", EVENT_MESSAGE),
            Map.entry("message", EVENT_MESSAGE), Map.entry("action", EVENT_ACTION),
            Map.entry("category", EVENT_CATEGORY), Map.entry("type", EVENT_TYPE),
            Map.entry("code", EVENT_CODE), Map.entry("event_id", EVENT_CODE),
            Map.entry("file", FILE_PATH), Map.entry("file_path", FILE_PATH),
            Map.entry("sha256", FILE_HASH_SHA256), Map.entry("file_hash", FILE_HASH_SHA256));

    /**
     * 把一条已解析的原始字段（key 为原始厂商字段名）转成 canonical 字段。
     * 未知键保留原名（不改写），已知别名映射到 canonical。
     */
    public static Map<String, String> canonicalize(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var en : raw.entrySet()) {
            String canonical = ALIASES.get(en.getKey());
            putIf(out, canonical != null ? canonical : en.getKey(), en.getValue());
        }
        return out;
    }

    /** CEF Extension 专用映射：短键 → canonical（CEF 标准字段缩写）。 */
    private static final Map<String, String> CEF_KEYS = Map.ofEntries(
            Map.entry("src", SOURCE_IP), Map.entry("spt", SOURCE_PORT),
            Map.entry("dst", DESTINATION_IP), Map.entry("dpt", DESTINATION_PORT),
            Map.entry("proto", NETWORK_PROTOCOL), Map.entry("cs1", USER_NAME),
            Map.entry("cs1Label", "cs1Label"), Map.entry("dvc", HOST_NAME),
            Map.entry("shost", HOST_NAME), Map.entry("suser", USER_NAME),
            Map.entry("duser", USER_NAME), Map.entry("act", EVENT_ACTION),
            Map.entry("rt", "timestamp"), Map.entry("msg", EVENT_MESSAGE));

    /** CEF Extension KV（key=value）转 canonical；未知 key 保留原名。 */
    public static Map<String, String> cefExt(Map<String, String> kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var en : kv.entrySet()) {
            String canonical = CEF_KEYS.get(en.getKey());
            putIf(out, canonical != null ? canonical : "cef." + en.getKey(), en.getValue());
        }
        return out;
    }
}
