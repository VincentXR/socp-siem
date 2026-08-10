package com.socp.search.config.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Linux auditd 解析器（JSON 事件，EXECVE/PATH/PROCTITLE 等）。
 * 把 audit 专有字段（exe/comm/uid/pid/a0..）映射到 canonical：
 * process.name / process.pid / process.command_line / user.name / event.action。
 */
public final class AuditdParser implements EventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String name() {
        return "auditd";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("{")) {
            return null;
        }
        Map<String, Object> obj;
        try {
            obj = MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            return null;
        }
        // auditd 特征：有 type 且为审计事件类型，或含 exe/comm/pid 等 audit 键
        boolean hasAuditType = obj.containsKey("type")
                && String.valueOf(obj.get("type")).matches("(EXECVE|PATH|PROCTITLE|SYSCALL|AUDIT|USER_CMD).*");
        boolean hasAuditFields = obj.containsKey("exe") || obj.containsKey("comm");
        if (!hasAuditType && !hasAuditFields) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "auditd");
        if (obj.get("type") != null) {
            out.put(CanonicalEvent.EVENT_ACTION, String.valueOf(obj.get("type")).toLowerCase());
        }
        if (obj.get("exe") != null) {
            out.put(CanonicalEvent.PROCESS_NAME, String.valueOf(obj.get("exe")));
        } else if (obj.get("comm") != null) {
            out.put(CanonicalEvent.PROCESS_NAME, String.valueOf(obj.get("comm")));
        }
        if (obj.get("pid") != null) {
            out.put(CanonicalEvent.PROCESS_PID, String.valueOf(obj.get("pid")));
        }
        if (obj.get("uid") != null) {
            out.put(CanonicalEvent.USER_NAME, String.valueOf(obj.get("uid")));
        }
        if (obj.get("key") != null) {
            out.put(CanonicalEvent.EVENT_CODE, String.valueOf(obj.get("key")));
        }
        // 参数 a0..a3 拼 command_line
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Object a = obj.get("a" + i);
            if (a != null) {
                if (!cmd.isEmpty()) cmd.append(' ');
                cmd.append(a);
            }
        }
        if (!cmd.isEmpty()) {
            out.put(CanonicalEvent.PROCESS_COMMAND_LINE, cmd.toString());
        }
        if (obj.get("cwd") != null) {
            out.put(CanonicalEvent.FILE_PATH, String.valueOf(obj.get("cwd")));
        }
        return out;
    }
}
