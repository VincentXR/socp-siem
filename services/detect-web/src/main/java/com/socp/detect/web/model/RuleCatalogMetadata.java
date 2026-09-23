package com.socp.detect.web.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Searchable fields derived from a rule, committed with its canonical spec. */
public record RuleCatalogMetadata(String name, String type, String status, String search,
                                  String references, String techniques) {
    public static RuleCatalogMetadata from(Map<String, Object> spec) {
        String name = text(spec.get("name"));
        String type = text(spec.get("type")).toLowerCase(Locale.ROOT);
        String status = text(spec.get("status")).toUpperCase(Locale.ROOT);
        if (!spec.containsKey("status")) status = Boolean.parseBoolean(text(spec.getOrDefault("enabled", true)))
                ? "ACTIVE" : "DISABLED";
        Set<String> references = new LinkedHashSet<>();
        // These are the executable condition trees. Extension metadata is not
        // itself a rule dependency.
        for (String key : List.of("match", "matchAny", "steps", "whitelist", "allowlist")) {
            collectReferences(spec.get(key), references);
        }
        Set<String> techniques = new TreeSet<>();
        addTechniques(spec.get("mitre"), techniques);
        addTechniques(spec.get("mitreIds"), techniques);
        return new RuleCatalogMetadata(name, type, status,
                (text(spec.get("id")) + "\n" + name + "\n" + type).toLowerCase(Locale.ROOT),
                String.join("", references), String.join("\n", techniques));
    }

    public static String referenceToken(String name) {
        return "|" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)) + "|";
    }

    private static void collectReferences(Object value, Set<String> references) {
        if (value instanceof List<?> values) {
            values.forEach(item -> collectReferences(item, references));
        } else if (value instanceof Map<?, ?> condition) {
            String op = text(condition.get("op")).toLowerCase(Locale.ROOT);
            if (("inlist".equals(op) || "notinlist".equals(op)) && condition.get("value") != null) {
                references.add(referenceToken(text(condition.get("value"))));
            }
        }
    }

    private static void addTechniques(Object value, Set<String> techniques) {
        if (value instanceof List<?> values) {
            values.forEach(item -> addTechniques(item, techniques));
        } else if (value instanceof String text) {
            for (String id : text.toUpperCase(Locale.ROOT).split("[\\s,;]+")) {
                if (id.matches("T[0-9]{4}(?:\\.[0-9]{3})?")) techniques.add(id);
            }
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
