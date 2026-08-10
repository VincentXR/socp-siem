package com.socp.search.config.parser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Syslog 解析器：支持 RFC3164（BSD）与 RFC5424（IETF）两种风格。
 *
 * <pre>
 * RFC3164: &lt;34&gt;Oct 11 22:14:15 mymachine su[1123]: 'su root' failed for lonvick on /dev/pts/8
 * RFC5424: &lt;165&gt;1 2003-10-11T22:14:15.003Z mymachine evntslog - ID47 [meta sequenceId="1"] msg
 * </pre>
 *
 * 输出：event.severity（按 PRI 换算，0-3 emergency/alert/critical/error，4-6 warning/notice/info，7 debug）、
 * host.name、event.message（正文）、vendor="syslog"，正文再交给 KV 等后续步骤（SourceRouter 可链式）。
 */
public final class SyslogParser implements EventParser {

    // <PRI>VERSION? TIMESTAMP HOST TAG[PID]: MSG 或 <PRI>TIMESTAMP HOST TAG[PID]: MSG
    private static final Pattern RFC5424 = Pattern.compile(
            "^<(?<pri>\\d{1,3})>\\s*(?<ver>\\d)\\s+(?<ts>\\S+)\\s+(?<host>\\S+)\\s+(?<app>\\S+)(?:\\s+\\S+){0,2}\\s*(?:\\[(?<pid>\\d+)])?\\s*:?\\s*(?<msg>.*)$");
    private static final Pattern RFC3164 = Pattern.compile(
            "^<(?<pri>\\d{1,3})>\\s*(?<ts>[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(?<host>\\S+)\\s+(?<app>\\S+)(?:\\[(?<pid>\\d+)])?\\s*:?\\s*(?<msg>.*)$");
    private static final DateTimeFormatter RFC3164_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneOffset.UTC);

    @Override
    public String name() {
        return "syslog";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("<")) {
            return null;
        }
        Matcher m = RFC5424.matcher(raw);
        if (!m.matches()) {
            m = RFC3164.matcher(raw);
        }
        if (!m.matches()) {
            return null; // 不是 syslog 形态（如 <tag> 出现在 KV 里），交给下一个 parser
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "syslog");
        out.put(CanonicalEvent.EVENT_MESSAGE, m.group("msg") == null ? "" : m.group("msg").trim());
        if (m.group("host") != null) {
            out.put(CanonicalEvent.HOST_NAME, m.group("host"));
        }
        out.put(CanonicalEvent.EVENT_CODE, m.group("app") == null ? "syslog" : m.group("app"));
        if (m.group("pid") != null) {
            out.put(CanonicalEvent.PROCESS_PID, m.group("pid"));
        }
        int pri = Integer.parseInt(m.group("pri"));
        out.put(CanonicalEvent.EVENT_SEVERITY, severityOf(pri & 0x07));
        String ts = m.group("ts");
        if (ts != null && !ts.isEmpty()) {
            try {
                Instant inst = ts.indexOf('T') > 0 ? Instant.parse(ts)
                        : RFC3164_FMT.parse(ts, Instant::from).plus(java.time.Duration.ofHours(0));
                out.put("timestamp", inst.toString());
            } catch (Exception ignored) {
                out.put("timestamp", ts);
            }
        }
        return out;
    }

    private static String severityOf(int sev) {
        return switch (sev) {
            case 0, 1, 2 -> "CRITICAL";
            case 3 -> "ERROR";
            case 4 -> "WARNING";
            case 6 -> "INFO";
            default -> "DEBUG";
        };
    }
}
