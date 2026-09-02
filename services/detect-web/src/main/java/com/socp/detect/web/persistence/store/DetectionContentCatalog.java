package com.socp.detect.web.persistence.store;


import com.socp.rule.util.Json;
import com.socp.rule.model.Severity;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Versioned, reviewable detection content metadata and contract pack. */
public final class DetectionContentCatalog {

    private static final String RESOURCE = "/detection-content/manifest.json";
    private static final Map<String, Object> MANIFEST = load();

    private DetectionContentCatalog() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> manifest() {
        return new LinkedHashMap<>(MANIFEST);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, Object>> metadataById() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object raw = MANIFEST.get("rules");
        if (raw instanceof List<?> rules) {
            for (Object item : rules) {
                if (item instanceof Map<?, ?> map && map.get("id") != null) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.put(String.valueOf(map.get("id")), copy);
                }
            }
        }
        return out;
    }

    /** Add package metadata without changing the executable RuleSpec contract. */
    public static Map<String, Object> enrich(Map<String, Object> input) {
        Map<String, Object> spec = new LinkedHashMap<>(input == null ? Map.of() : input);
        String id = String.valueOf(spec.getOrDefault("id", ""));
        Map<String, Map<String, Object>> metadata = metadataById();
        Map<String, Object> meta = metadata.get(id);
        if (meta != null) {
            copyIfMissing(spec, "contentPack", MANIFEST.get("packId"));
            copyIfMissing(spec, "contentVersion", MANIFEST.get("version"));
            copyIfMissing(spec, "version", meta.get("version"));
            copyIfMissing(spec, "status", meta.get("status"));
            copyIfMissing(spec, "owner", meta.get("owner"));
            copyIfMissing(spec, "description", meta.get("description"));
            copyIfMissing(spec, "dataSources", meta.get("dataSources"));
            copyIfMissing(spec, "mitreIds", meta.get("mitre"));
            copyIfMissing(spec, "references", meta.get("references"));
        } else {
            copyIfMissing(spec, "contentPack", MANIFEST.get("packId"));
            copyIfMissing(spec, "contentVersion", MANIFEST.get("version"));
            copyIfMissing(spec, "version", "0.1.0");
            copyIfMissing(spec, "status", Boolean.parseBoolean(String.valueOf(spec.getOrDefault("enabled", true)))
                    ? "ACTIVE" : "DISABLED");
            copyIfMissing(spec, "owner", "unowned");
        }
        // Stateful detection is partition-local only when the rule grouping
        // dimension is the same dimension encoded in the Kafka key.  Preserve
        // compatibility for legacy specs while making the contract explicit
        // in every persisted/returned rule document.
        String type = String.valueOf(spec.getOrDefault("type", "")).toLowerCase();
        if (List.of("threshold", "correlation", "correlation-set", "baseline", "rare").contains(type)) {
            copyIfMissing(spec, "routingField", spec.get("keyField"));
        }
        // The lifecycle status is authoritative for the executable engine.
        // Expose the derived legacy flag as well so older API clients (and
        // the workbench rule table) can render and operate on packaged rules
        // that predate the explicit status field.
        if (!spec.containsKey("enabled")) {
            spec.put("enabled", "ACTIVE".equalsIgnoreCase(String.valueOf(spec.get("status"))));
        }
        return spec;
    }

    public static List<String> validateSpec(Map<String, Object> spec) {
        List<String> errors = new ArrayList<>();
        if (spec == null) return List.of("spec is null");
        for (String field : List.of("id", "name", "type", "severity", "version", "owner")) {
            if (spec.get(field) == null || String.valueOf(spec.get(field)).isBlank()) {
                errors.add("missing " + field);
            }
        }
        String type = String.valueOf(spec.getOrDefault("type", "")).toLowerCase();
        if (!List.of("pattern", "threshold", "correlation", "correlation-set", "baseline", "rare").contains(type)) {
            errors.add("unsupported type " + type);
        }
        try {
            Severity.valueOf(String.valueOf(spec.getOrDefault("severity", "")).toUpperCase());
        } catch (Exception ignored) {
            errors.add("severity must be one of INFO, LOW, MEDIUM, HIGH, CRITICAL");
        }
        Object status = spec.get("status");
        if (status != null && !List.of("DRAFT", "TESTING", "ACTIVE", "DISABLED", "ARCHIVED")
                .contains(String.valueOf(status).toUpperCase())) {
            errors.add("invalid status " + status);
        }
        if (("threshold".equals(type) || "correlation".equals(type) || "correlation-set".equals(type)
                || "baseline".equals(type) || "rare".equals(type))
                && (spec.get("keyField") == null || String.valueOf(spec.get("keyField")).isBlank())) {
            errors.add("stateful rule requires keyField");
        }
        if (List.of("threshold", "correlation", "correlation-set", "baseline", "rare").contains(type)
                && spec.get("keyField") != null && !String.valueOf(spec.get("keyField")).isBlank()
                && (spec.get("routingField") == null || String.valueOf(spec.get("routingField")).isBlank())) {
            errors.add("stateful rule requires routingField");
        }
        if (spec.get("keyField") != null && spec.get("routingField") != null
                && !String.valueOf(spec.get("keyField")).trim()
                .equals(String.valueOf(spec.get("routingField")).trim())) {
            errors.add("keyField and routingField must match for partition-local state");
        }
        if ("rare".equals(type)
                && (spec.get("valueField") == null || String.valueOf(spec.get("valueField")).isBlank())) {
            errors.add("rare rule requires valueField");
        }
        if ("threshold".equals(type) && (!(spec.get("threshold") instanceof Number)
                || ((Number) spec.get("threshold")).intValue() <= 0)) {
            errors.add("threshold must be a positive number");
        }
        if (List.of("correlation", "correlation-set").contains(type)
                && (!(spec.get("steps") instanceof List<?>) || ((List<?>) spec.get("steps")).size() < 2)) {
            errors.add("correlation rule requires at least two steps");
        }
        validateConditions(errors, spec.get("match"), "match");
        validateConditionGroups(errors, spec.get("matchAny"), "matchAny");
        if (spec.get("steps") instanceof List<?> steps) {
            for (int i = 0; i < steps.size(); i++) {
                Object step = steps.get(i);
                if (!(step instanceof List<?>)) {
                    errors.add("steps[" + i + "] must be an array");
                } else {
                    validateConditions(errors, step, "steps[" + i + "]");
                }
            }
        }
        return errors;
    }

    private static void validateConditionGroups(List<String> errors, Object raw, String label) {
        if (raw == null) return;
        if (!(raw instanceof List<?> groups)) {
            errors.add(label + " must be an array");
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            Object group = groups.get(i);
            if (!(group instanceof List<?>)) errors.add(label + "[" + i + "] must be an array");
            else validateConditions(errors, group, label + "[" + i + "]");
        }
    }

    private static void validateConditions(List<String> errors, Object raw, String label) {
        if (raw == null) return;
        if (!(raw instanceof List<?> conditions)) {
            errors.add(label + " must be an array");
            return;
        }
        for (int i = 0; i < conditions.size(); i++) {
            Object condition = conditions.get(i);
            if (!(condition instanceof Map<?, ?> map)
                    || map.get("field") == null || String.valueOf(map.get("field")).isBlank()
                    || map.get("op") == null || map.get("value") == null) {
                errors.add(label + "[" + i + "] requires field/op/value");
                continue;
            }
            String op = String.valueOf(map.get("op")).toLowerCase();
            if (!List.of("eq", "ne", "contains", "startswith", "endswith", "ge", "gtsev",
                    "regex", "gt", "gte", "lt", "lte", "inlist", "notinlist").contains(op)) {
                errors.add(label + "[" + i + "] unsupported op " + op);
            }
            if ("regex".equals(op)) {
                try {
                    Pattern.compile(String.valueOf(map.get("value")));
                } catch (Exception ex) {
                    errors.add(label + "[" + i + "] invalid regex");
                }
            }
        }
    }

    private static void copyIfMissing(Map<String, Object> target, String key, Object value) {
        if (!target.containsKey(key) && value != null) target.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream in = DetectionContentCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            return Json.mapper().readValue(in, Map.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
