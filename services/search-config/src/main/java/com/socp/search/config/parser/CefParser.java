package com.socp.search.config.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** ArcSight CEF headers and extensions, including escaped pipes and multiword values. */
public final class CefParser implements EventParser {
    private static final Pattern EXTENSION_KEY = Pattern.compile("(?<!\\S)([A-Za-z][A-Za-z0-9_.-]*)=");

    @Override
    public String name() { return "cef"; }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.startsWith("CEF:")) return null;
        var parts = new ArrayList<String>();
        var field = new StringBuilder();
        int offset = 4;
        for (; offset < raw.length() && parts.size() < 7; offset++) {
            char c = raw.charAt(offset);
            if (c == '\\' && offset + 1 < raw.length()
                    && (raw.charAt(offset + 1) == '|' || raw.charAt(offset + 1) == '\\')) {
                field.append(raw.charAt(++offset));
            } else if (c == '|') {
                parts.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        // Retain compatibility with producers omitting the final empty extension separator.
        if (parts.size() == 6) parts.add(field.toString());
        if (parts.size() != 7) throw new IllegalArgumentException("CEF requires seven header fields");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "cef");
        out.put("cef.device.vendor", parts.get(1).trim());
        out.put("cef.device.product", parts.get(2).trim());
        out.put("cef.device.version", parts.get(3).trim());
        out.put(CanonicalEvent.EVENT_CODE, parts.get(4).trim());
        out.put(CanonicalEvent.EVENT_ACTION, parts.get(5).trim());
        out.put(CanonicalEvent.EVENT_SEVERITY, SecurityLogSeverity.normalize(parts.get(6)));
        out.putAll(CanonicalEvent.cefExt(extensions(raw.substring(offset))));
        return out;
    }

    private static Map<String, String> extensions(String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        var matcher = EXTENSION_KEY.matcher(value);
        String key = null;
        int start = 0;
        while (matcher.find()) {
            if (key != null) fields.put(key, unescape(value.substring(start, matcher.start()).stripTrailing()));
            else if (!value.substring(0, matcher.start()).isBlank()) {
                throw new IllegalArgumentException("invalid CEF extension");
            }
            key = matcher.group(1);
            start = matcher.end();
        }
        if (key != null) fields.put(key, unescape(value.substring(start)));
        else if (!value.isBlank()) throw new IllegalArgumentException("invalid CEF extension");
        return fields;
    }

    private static String unescape(String value) {
        var out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case '\\', '=' -> { out.append(next); i++; }
                    case 'n' -> { out.append('\n'); i++; }
                    case 'r' -> { out.append('\r'); i++; }
                    default -> out.append(c);
                }
            } else out.append(c);
        }
        return out.toString();
    }
}
