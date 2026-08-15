package com.socp.search.config.parser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the common plain-text sshd file format used by Linux auth logs.
 *
 * <p>Vector transports the raw line; this parser is responsible for the
 * security meaning: authentication category/action, account and source IP.
 * It accepts both ISO-8601 demo lines and the host-prefixed shape commonly
 * written by rsyslog.</p>
 */
public final class SshdParser implements EventParser {

    private static final Pattern LINE = Pattern.compile(
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}T\\S+|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})\\s+"
                    + "(?:(?<host>\\S+)\\s+)?sshd(?:\\[(?<pid>\\d+)])?:\\s+(?<message>.*)$");
    private static final Pattern FAILED = Pattern.compile(
            "Failed password for (?:invalid user )?(?<user>\\S+) from (?<ip>\\d{1,3}(?:\\.\\d{1,3}){3})"
                    + "(?: port (?<port>\\d+))?");
    private static final Pattern ACCEPTED = Pattern.compile(
            "Accepted \\S+ for (?<user>\\S+) from (?<ip>\\d{1,3}(?:\\.\\d{1,3}){3})"
                    + "(?: port (?<port>\\d+))?");

    @Override
    public String name() {
        return "sshd";
    }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null) return null;
        Matcher line = LINE.matcher(raw.trim());
        if (!line.matches()) return null;

        String message = line.group("message");
        Map<String, String> out = new LinkedHashMap<>();
        out.put(CanonicalEvent.EVENT_CODE, "sshd");
        out.put(CanonicalEvent.EVENT_CATEGORY, "authentication");
        out.put(CanonicalEvent.EVENT_MESSAGE, message);
        out.put("vendor", "sshd");
        CanonicalEvent.putIf(out, CanonicalEvent.HOST_NAME, line.group("host"));
        CanonicalEvent.putIf(out, CanonicalEvent.PROCESS_NAME, "sshd");
        CanonicalEvent.putIf(out, CanonicalEvent.PROCESS_PID, line.group("pid"));
        putTimestamp(out, line.group("timestamp"));

        Matcher failed = FAILED.matcher(message);
        if (failed.find()) {
            out.put(CanonicalEvent.EVENT_ACTION, "login_failed");
            out.put(CanonicalEvent.EVENT_SEVERITY, "WARNING");
            putAuthFields(out, failed);
            return out;
        }

        Matcher accepted = ACCEPTED.matcher(message);
        if (accepted.find()) {
            out.put(CanonicalEvent.EVENT_ACTION, "login_success");
            out.put(CanonicalEvent.EVENT_SEVERITY, "INFO");
            putAuthFields(out, accepted);
            return out;
        }

        return out;
    }

    private static void putAuthFields(Map<String, String> out, Matcher m) {
        CanonicalEvent.putIf(out, CanonicalEvent.USER_NAME, m.group("user"));
        CanonicalEvent.putIf(out, CanonicalEvent.SOURCE_IP, m.group("ip"));
        CanonicalEvent.putIf(out, CanonicalEvent.SOURCE_PORT, m.group("port"));
    }

    private static void putTimestamp(Map<String, String> out, String value) {
        if (value == null || value.isBlank() || !value.contains("T")) return;
        try {
            out.put("timestamp", Instant.parse(value).toString());
        } catch (Exception ignored) {
            // The pipeline will use ingestion time when a legacy timestamp is ambiguous.
        }
    }
}
