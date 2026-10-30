package com.socp.search.config.parser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** RFC3164 and RFC5424. Yearless timestamps are resolved against trusted receipt time at ingestion. */
public final class SyslogParser implements EventParser {
    private static final Pattern RFC5424 = Pattern.compile(
            "^<(?<pri>\\d{1,3})>(?<ver>[1-9]\\d{0,2}) (?<ts>\\S+) (?<host>\\S+) (?<app>\\S+) (?<pid>\\S+) (?<msgid>\\S+) (?<data>.*)$",
            Pattern.DOTALL);
    private static final Pattern RFC3164 = Pattern.compile(
            "^<(?<pri>\\d{1,3})>\\s*(?<ts>[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+(?<host>\\S+)\\s+(?<app>[^\\s\\[:]+)(?:\\[(?<pid>\\d+)])?\\s*:?\\s*(?<msg>.*)$",
            Pattern.DOTALL);

    @Override
    public String name() { return "syslog"; }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.stripLeading().startsWith("<")) return null;
        var m = RFC5424.matcher(raw.stripLeading());
        boolean ietf = m.matches();
        if (!ietf) m = RFC3164.matcher(raw.stripLeading());
        if (!m.matches()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "syslog");
        out.put("syslog.version", ietf ? m.group("ver") : "3164");
        out.put(CanonicalEvent.EVENT_MESSAGE, ietf ? structuredData(m.group("data"), out) : m.group("msg"));
        put(out, CanonicalEvent.HOST_NAME, m.group("host"));
        String app = m.group("app");
        put(out, CanonicalEvent.EVENT_CODE, app);
        put(out, CanonicalEvent.PROCESS_NAME, app);
        out.put("source", switch (app.toLowerCase(Locale.ROOT)) {
            case "sshd", "sudo", "su" -> "auth";
            case "auditd", "linux", "edr", "falco" -> app.toLowerCase(Locale.ROOT);
            default -> "syslog";
        });
        String pid = m.group("pid");
        put(out, "syslog.procid", pid);
        if (pid != null && pid.matches("[0-9]+")) out.put(CanonicalEvent.PROCESS_PID, pid);
        if (ietf) put(out, "syslog.msgid", m.group("msgid"));
        int pri = Integer.parseInt(m.group("pri"));
        if (pri > 191) throw new IllegalArgumentException("syslog PRI exceeds 191");
        out.put("syslog.facility", Integer.toString(pri / 8));
        out.put(CanonicalEvent.EVENT_SEVERITY, severityOf(pri & 7));
        String ts = m.group("ts");
        if (!"-".equals(ts)) {
            try {
                out.put("timestamp", ietf ? Instant.parse(ts).toString() : ts);
            } catch (java.time.DateTimeException invalid) {
                throw new IllegalArgumentException("invalid RFC5424 timestamp", invalid);
            }
        }
        if ("sshd".equalsIgnoreCase(app)) {
            Map<String, String> authentication = new SshdParser().parse(
                    ts + " " + m.group("host") + " sshd: " + out.get(CanonicalEvent.EVENT_MESSAGE));
            if (authentication != null) {
                out.putAll(authentication);
                out.put("vendor", "syslog");
            }
        }
        return out;
    }

    /** Parse SD separately from MSG; escaped quotes, backslashes and brackets belong to PARAM-VALUE. */
    private static String structuredData(String data, Map<String, String> out) {
        int position = 0;
        if (data.startsWith("-")) position = 1;
        else {
            while (position < data.length() && data.charAt(position) == '[') {
                int start = ++position;
                while (position < data.length() && data.charAt(position) != ' ' && data.charAt(position) != ']') position++;
                String id = sdName(data.substring(start, position));
                while (position < data.length() && data.charAt(position) == ' ') {
                    start = ++position;
                    while (position < data.length() && data.charAt(position) != '=') position++;
                    String name = sdName(data.substring(start, position));
                    if (++position >= data.length() || data.charAt(position++) != '"') throw invalidData();
                    var value = new StringBuilder();
                    boolean closed = false;
                    while (position < data.length()) {
                        char c = data.charAt(position++);
                        if (c == '"') { closed = true; break; }
                        if (c == ']') throw invalidData();
                        if (c == '\\' && position < data.length()) {
                            char next = data.charAt(position);
                            if (next == '"' || next == '\\' || next == ']') { value.append(next); position++; continue; }
                        }
                        value.append(c);
                    }
                    if (!closed) throw invalidData();
                    out.put("syslog.sd." + id + "." + name, value.toString());
                }
                if (position >= data.length() || data.charAt(position++) != ']') throw invalidData();
            }
            if (position == 0) throw invalidData();
            out.put("syslog.structured_data", data.substring(0, position));
        }
        if (position == data.length()) return "";
        if (data.charAt(position) != ' ') throw invalidData();
        return data.substring(position + 1);
    }

    private static String sdName(String value) {
        if (value.isEmpty() || value.length() > 32 || value.chars().anyMatch(
                c -> c < 33 || c > 126 || c == '=' || c == '"' || c == ']')) throw invalidData();
        return value;
    }

    private static IllegalArgumentException invalidData() {
        return new IllegalArgumentException("invalid RFC5424 structured data");
    }

    private static void put(Map<String, String> out, String key, String value) {
        if (value != null && !"-".equals(value)) out.put(key, value);
    }

    private static String severityOf(int severity) {
        return switch (severity) {
            case 0, 1, 2 -> "CRITICAL";
            case 3 -> "ERROR";
            case 4 -> "WARNING";
            case 5 -> "NOTICE";
            case 6 -> "INFO";
            default -> "DEBUG";
        };
    }
}
