package com.socp.search.config.parser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** IBM LEEF 1.0 uses tab-separated attributes; 2.0 declares its attribute delimiter. */
public final class LeefParser implements EventParser {
    @Override
    public String name() { return "leef"; }

    @Override
    public Map<String, String> parse(String raw) {
        if (raw == null || !raw.startsWith("LEEF:")) return null;
        boolean versionTwo = raw.startsWith("LEEF:2.0|");
        if (!versionTwo && !raw.startsWith("LEEF:1.0|")) {
            throw new IllegalArgumentException("unsupported LEEF version");
        }
        String[] parts = raw.substring(5).split("\\|", versionTwo ? 7 : 6);
        if (parts.length != (versionTwo ? 7 : 6)) throw new IllegalArgumentException("incomplete LEEF header");
        String separator = versionTwo ? delimiter(parts[5]) : "\t";
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String attribute : parts[versionTwo ? 6 : 5].split(Pattern.quote(separator), -1)) {
            if (attribute.isEmpty()) continue;
            int equals = attribute.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("invalid LEEF attribute");
            attributes.put(attribute.substring(0, equals).trim(), attribute.substring(equals + 1));
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("vendor", "leef");
        out.put("leef.device.vendor", parts[1]);
        out.put("leef.device.product", parts[2]);
        out.put("leef.device.version", parts[3]);
        out.put(CanonicalEvent.EVENT_CODE, parts[4]);
        out.put(CanonicalEvent.EVENT_SEVERITY, SecurityLogSeverity.normalize(attributes.get("sev")));
        // LEEF shares network keys with CEF but uses srcPort/dstPort/usrName.
        attributes.forEach((key, value) -> {
            String alias = switch (key) {
                case "srcPort" -> "spt";
                case "dstPort" -> "dpt";
                case "usrName" -> "suser";
                default -> key;
            };
            CanonicalEvent.cefExt(Map.of(alias, value)).forEach((mapped, text) ->
                    out.put(mapped.startsWith("cef.") ? "leef." + mapped.substring(4) : mapped, text));
        });
        return out;
    }

    private static String delimiter(String value) {
        if (value.length() == 1) return value;
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2)
                : value.startsWith("x") || value.startsWith("X") ? value.substring(1) : "";
        if (!hex.matches("[0-9a-fA-F]{1,4}")) throw new IllegalArgumentException("invalid LEEF delimiter");
        return String.valueOf((char) Integer.parseInt(hex, 16));
    }
}
