package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Windows Sysmon 解析器：Sysmon 的 JSON 事件（EventID 1/3/5/11/22 等）。
 * Sysmon 本身是 JSON（见 {@link JsonParser}），这里按 EventID 做 canonical 增强：
 * 把 Sysmon 专有字段映射到统一模型，让 Detection Rule 不必区分厂商。
 *
 * <pre>
 * {"Event":{"EventData":{"CommandLine":"...","Image":"C:\\...","ProcessId":"1234","User":"...","SourceIp":"1.2.3.4"},...}}
 * </pre>
 */
public final class SysmonParser implements EventParser {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public String name() {
        return "sysmon";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return null;
        }
        Map<String, Object> root;
        try {
            root = MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            return null; // 非 JSON，交下一个
        }
        Object evtObj = root.get("Event");
        if (!(evtObj instanceof Map<?, ?> evt)) {
            return null;
        }
        Object dataObj = evt.get("EventData");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> ed = (Map<String, Object>) data;
        Object eidObj = evt.get("EventID") != null ? evt.get("EventID") : ed.get("EventID");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "sysmon");
        out.put(CanonicalEvent.EVENT_CODE, String.valueOf(eidObj == null ? "0" : eidObj));
        out.put(CanonicalEvent.EVENT_CATEGORY, "process");
        map(out, ed, "Image", CanonicalEvent.PROCESS_NAME);
        map(out, ed, "ProcessId", CanonicalEvent.PROCESS_PID);
        map(out, ed, "CommandLine", CanonicalEvent.PROCESS_COMMAND_LINE);
        map(out, ed, "User", CanonicalEvent.USER_NAME);
        map(out, ed, "SourceIp", CanonicalEvent.SOURCE_IP);
        map(out, ed, "SourcePort", CanonicalEvent.SOURCE_PORT);
        map(out, ed, "DestinationIp", CanonicalEvent.DESTINATION_IP);
        map(out, ed, "DestinationPort", CanonicalEvent.DESTINATION_PORT);
        map(out, ed, "Protocol", CanonicalEvent.NETWORK_PROTOCOL);
        map(out, ed, "FileName", CanonicalEvent.FILE_PATH);
        map(out, ed, "Hashes", CanonicalEvent.FILE_HASH_SHA256);
        map(out, ed, "ParentImage", CanonicalEvent.PROCESS_COMMAND_LINE);
        // 事件动作：按 EventID 语义（1=process_start, 3=network_connection, 11=file_create, 22=dns_query）
        out.put(CanonicalEvent.EVENT_TYPE, sysmonType(String.valueOf(eidObj)));
        return out;
    }

    private static void map(Map<String, String> out, Map<String, Object> ed, String key, String canonical) {
        Object v = ed.get(key);
        if (v != null) {
            CanonicalEvent.putIf(out, canonical, v);
        }
    }

    private static String sysmonType(String eid) {
        return switch (eid) {
            case "1" -> "process_start";
            case "3" -> "network_connection";
            case "5" -> "process_terminate";
            case "11" -> "file_create";
            case "22" -> "dns_query";
            default -> "event";
        };
    }
}
