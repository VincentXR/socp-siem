package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared grouping/routing dimension resolver.
 *
 * <p>A simple dimension resolves a canonical field plus its documented aliases.
 * A composite dimension uses {@code component+component}; every component is
 * required and values are length-prefixed so the representation is unambiguous.</p>
 */
public final class RoutingDimension {

    public static final int MAX_COMPOSITE_COMPONENTS = 4;

    private static final Map<String, List<String>> ALIASES = aliases();

    private RoutingDimension() {
    }

    public static String value(SecurityEvent event, String expression) {
        if (event == null) return null;
        List<String> parts = components(expression);
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return simpleValue(event, parts.get(0));
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String value = simpleValue(event, part);
            if (value == null) return null;
            if (!out.isEmpty()) out.append('|');
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    public static List<String> components(String expression) {
        if (expression == null || expression.isBlank()) return List.of();
        String[] raw = expression.trim().split("\\+");
        if (raw.length == 0 || raw.length > MAX_COMPOSITE_COMPONENTS) return List.of();
        List<String> out = new ArrayList<>(raw.length);
        for (String item : raw) {
            String normalized = normalize(item);
            if (normalized == null) return List.of();
            out.add(normalized);
        }
        return List.copyOf(out);
    }

    public static List<String> validationErrors(String expression) {
        List<String> errors = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            errors.add("routing dimension is required");
            return errors;
        }
        String[] raw = expression.trim().split("\\+");
        if (raw.length > MAX_COMPOSITE_COMPONENTS) {
            errors.add("routing dimension has more than " + MAX_COMPOSITE_COMPONENTS + " components");
            return errors;
        }
        for (String item : raw) {
            if (normalize(item) == null) {
                errors.add("invalid routing dimension component '" + item + "'");
            }
        }
        return errors;
    }

    public static Map<String, List<String>> aliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("user", List.of("user", "username", "user_name", "user.name"));
        aliases.put("host", List.of("host", "hostname", "host_name", "host.name"));
        aliases.put("src_ip", List.of("src_ip", "srcIp", "source_ip", "source.ip", "client_ip", "client.ip"));
        aliases.put("dst_ip", List.of("dst_ip", "dstIp", "destination_ip", "destination.ip", "dest_ip"));
        return Map.copyOf(aliases);
    }

    private static String simpleValue(SecurityEvent event, String component) {
        if ("source".equals(component)) return clean(event.source());
        if ("host".equals(component)) {
            String fromFields = first(event.fields(), ALIASES.get("host"));
            return fromFields == null ? clean(event.host()) : fromFields;
        }
        List<String> names = ALIASES.getOrDefault(component, List.of(component));
        return first(event.fields(), names);
    }

    private static String first(Map<String, String> fields, List<String> names) {
        if (fields == null || names == null) return null;
        for (String name : names) {
            String value = clean(fields.get(name));
            if (value != null) return value;
        }
        return null;
    }

    private static String normalize(String value) {
        String normalized = clean(value);
        if (normalized == null || normalized.length() > 128) return null;
        if (!normalized.matches("[A-Za-z0-9_.-]+")) return null;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        return value.trim();
    }
}
