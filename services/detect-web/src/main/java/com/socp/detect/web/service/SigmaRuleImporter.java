package com.socp.detect.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.socp.detect.web.persistence.store.DetectionContentCatalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts a deliberately small, semantics-preserving Sigma subset to the
 * executable SOCP rule contract. Unsupported constructs fail explicitly; a
 * SIEM must never silently turn an OR/NOT rule into an AND rule.
 *
 * <p>Supported: scalar selections, AND/OR, {@code 1 of}/{@code all of}
 * selection groups, {@code contains}/{@code startswith}/{@code endswith}/{@code re}
 * modifiers, timeframe and common logsource metadata. NOT, aggregations,
 * condition arithmetic and list-valued predicates are rejected until the
 * internal RuleSpec model can represent them without changing meaning.</p>
 */
public final class SigmaRuleImporter {

    private static final int MAX_SOURCE_BYTES = 512 * 1024;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern ATTACK = Pattern.compile("(?i)(T\\d{4}(?:\\.\\d{3})?)");
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ImportResult importRule(String source) {
        if (source == null || source.isBlank()) throw invalid("Sigma source is empty");
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw invalid("Sigma source exceeds 512 KiB");
        }
        Map<String, Object> root;
        try {
            root = yaml.readValue(source, new TypeReference<>() { });
        } catch (Exception ex) {
            throw invalid("invalid Sigma YAML: " + ex.getMessage());
        }
        if (root == null) throw invalid("Sigma document must be a mapping");
        String title = requiredText(root, "title");
        Map<String, Object> detection = map(root.get("detection"), "detection");
        String condition = requiredText(detection, "condition");
        ConditionPlan plan = parseCondition(condition, detection);
        List<Map<String, Object>> match = new ArrayList<>();
        for (String selection : plan.required()) {
            Map<String, Object> values = map(detection.get(selection), "detection." + selection);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                match.add(condition(entry.getKey(), entry.getValue(), "detection." + selection));
            }
        }
        List<List<Map<String, Object>>> matchAny = new ArrayList<>();
        for (String selection : plan.any()) {
            Map<String, Object> values = map(detection.get(selection), "detection." + selection);
            List<Map<String, Object>> group = new ArrayList<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                group.add(condition(entry.getKey(), entry.getValue(), "detection." + selection));
            }
            if (!group.isEmpty()) matchAny.add(group);
        }
        if (match.isEmpty() && matchAny.isEmpty()) throw invalid("Sigma selection has no scalar predicates");

        String id = text(root.get("id"));
        if (id == null) id = "SIGMA-" + digest(title + "\n" + source).substring(0, 16).toUpperCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) throw invalid("Sigma id is not a safe rule id");

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", id);
        spec.put("name", title);
        spec.put("type", "pattern");
        spec.put("severity", severity(root.get("level")));
        spec.put("message", text(root.get("description")) == null ? title : text(root.get("description")));
        spec.put("version", text(root.get("version")) == null ? "sigma-1" : text(root.get("version")));
        spec.put("status", lifecycle(root.get("status")));
        spec.put("owner", "sigma-import");
        spec.put("contentPack", "sigma-import");
        spec.put("contentVersion", "1");
        spec.put("match", match);
        if (!matchAny.isEmpty()) spec.put("matchAny", matchAny);
        String timeframe = text(root.get("timeframe"));
        if (timeframe != null) spec.put("window", normalizeWindow(timeframe));
        String attack = attackTag(root.get("tags"));
        if (attack != null) spec.put("mitre", attack);
        spec.put("sigmaSource", source);
        spec.put("sigmaVersion", text(root.get("sigma_version")) == null ? "2.0" : text(root.get("sigma_version")));
        spec.put("sigmaStatus", text(root.get("status")) == null ? "unknown" : text(root.get("status")));
        spec.put("sigmaLogsource", root.getOrDefault("logsource", Map.of()));
        spec.put("dataSources", dataSources(root.get("logsource")));

        List<String> errors = DetectionContentCatalog.validateSpec(spec);
        if (!errors.isEmpty()) throw invalid("Sigma conversion failed: " + String.join(", ", errors));
        List<String> selected = new ArrayList<>(plan.required());
        selected.addAll(plan.any());
        return new ImportResult(spec, id, selected, condition);
    }

    private static ConditionPlan parseCondition(String condition, Map<String, Object> detection) {
        String normalized = condition.trim().replaceAll("\\s+", " ");
        if (normalized.matches("(?i).*\\bnot\\b.*")
                || normalized.matches("(?i).*\\b(count|near|before|after)\\s*\\(.*")) {
            throw invalid("unsupported Sigma condition: " + condition);
        }
        normalized = stripOuterParentheses(normalized);
        java.util.regex.Matcher group = Pattern.compile("(?i)^(\\d+|all)\\s+of\\s+(.+)$").matcher(normalized);
        if (group.matches()) {
            int required = "all".equalsIgnoreCase(group.group(1)) ? Integer.MAX_VALUE
                    : Integer.parseInt(group.group(1));
            List<String> candidates = expandSelections(group.group(2), detection);
            if (candidates.isEmpty()) throw invalid("Sigma condition selects no detection blocks: " + condition);
            if (required == Integer.MAX_VALUE) return new ConditionPlan(candidates, List.of());
            if (required == 1) return new ConditionPlan(List.of(), candidates);
            throw invalid("only '1 of' and 'all of' Sigma conditions are supported: " + condition);
        }
        if (normalized.contains(" or ")) {
            List<String> names = selectionNames(normalized.split("(?i)\\s+or\\s+"), detection);
            return new ConditionPlan(List.of(), names);
        }
        String[] pieces = normalized.split("(?i)\\s+and\\s+");
        return new ConditionPlan(selectionNames(pieces, detection), List.of());
    }

    private static List<String> selectionNames(String[] pieces, Map<String, Object> detection) {
        List<String> names = new ArrayList<>();
        for (String piece : pieces) {
            String name = stripOuterParentheses(piece.trim());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*") || !detection.containsKey(name)) {
                throw invalid("unsupported or missing Sigma selection: " + name);
            }
            names.add(name);
        }
        return names;
    }

    private static List<String> expandSelections(String expression, Map<String, Object> detection) {
        String value = stripOuterParentheses(expression.trim());
        if ("them".equalsIgnoreCase(value)) {
            return detection.keySet().stream().filter(key -> !"condition".equals(key)).toList();
        }
        if (!value.endsWith("*")) return selectionNames(new String[]{value}, detection);
        String prefix = value.substring(0, value.length() - 1);
        if (!prefix.matches("[A-Za-z_][A-Za-z0-9_-]*")) {
            throw invalid("invalid Sigma selection wildcard: " + value);
        }
        return detection.keySet().stream()
                .filter(key -> !"condition".equals(key) && key.startsWith(prefix))
                .toList();
    }

    private static String stripOuterParentheses(String value) {
        String result = value.trim();
        while (result.startsWith("(") && result.endsWith(")")) {
            int depth = 0;
            boolean wrapsAll = true;
            for (int i = 0; i < result.length(); i++) {
                char current = result.charAt(i);
                if (current == '(') depth++;
                else if (current == ')' && --depth == 0 && i != result.length() - 1) {
                    wrapsAll = false;
                    break;
                }
            }
            if (!wrapsAll) break;
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static Map<String, Object> condition(String rawField, Object rawValue, String path) {
        String[] parts = rawField.split("\\|", 2);
        String field = mapField(parts[0]);
        String op = parts.length == 1 ? "eq" : switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "contains" -> "contains";
            case "startswith" -> "startswith";
            case "endswith" -> "endswith";
            case "re", "regex" -> "regex";
            default -> throw invalid("unsupported Sigma modifier at " + path + ": " + parts[1]);
        };
        if (rawValue instanceof List<?> || rawValue instanceof Map<?, ?> || rawValue == null) {
            throw invalid("non-scalar Sigma value at " + path + "." + rawField);
        }
        String value = String.valueOf(rawValue);
        if ("regex".equals(op)) {
            try { Pattern.compile(value); }
            catch (RuntimeException ex) { throw invalid("invalid Sigma regex at " + path); }
        }
        return Map.of("field", field, "op", op, "value", value);
    }

    private static String mapField(String field) {
        String normalized = field.trim();
        return switch (normalized) {
            case "EventID", "EventId", "event_id" -> "event_id";
            case "Image", "Executable", "process.executable" -> "process_executable";
            case "CommandLine", "CommandLine|contains", "process.command_line" -> "process_command_line";
            case "Computer", "ComputerName", "host.hostname" -> "host";
            case "User", "UserName", "user.name" -> "user_name";
            case "SourceIp", "SourceIP", "source.ip" -> "src_ip";
            case "DestinationIp", "DestinationIP", "destination.ip" -> "dst_ip";
            default -> normalized.replace('.', '_');
        };
    }

    private static String severity(Object value) {
        String level = text(value);
        return switch (level == null ? "" : level.toLowerCase(Locale.ROOT)) {
            case "critical" -> "CRITICAL";
            case "high" -> "HIGH";
            case "medium" -> "MEDIUM";
            case "low" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private static String lifecycle(Object value) {
        String status = text(value);
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "stable", "production" -> "ACTIVE";
            case "test" -> "TESTING";
            case "deprecated" -> "ARCHIVED";
            default -> "DRAFT";
        };
    }

    private static String normalizeWindow(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("\\d+ms")) return Math.max(1, Long.parseLong(normalized.replace("ms", "")) / 1000) + "s";
        if (normalized.matches("\\d+[smhd]")) return normalized;
        throw invalid("unsupported Sigma timeframe: " + value);
    }

    private static List<String> dataSources(Object value) {
        if (!(value instanceof Map<?, ?> map)) return List.of("sigma");
        List<String> out = new ArrayList<>();
        for (String key : List.of("product", "service", "category")) {
            String item = text(map.get(key));
            if (item != null) out.add(item.toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? List.of("sigma") : List.copyOf(out);
    }

    private static String attackTag(Object tags) {
        if (!(tags instanceof List<?> list)) return null;
        for (Object tag : list) {
            var matcher = ATTACK.matcher(String.valueOf(tag));
            if (matcher.find()) return matcher.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    private static Map<String, Object> map(Object value, String path) {
        if (!(value instanceof Map<?, ?> raw)) throw invalid(path + " must be a mapping");
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static String requiredText(Map<String, Object> map, String key) {
        String value = text(map.get(key));
        if (value == null) throw invalid("missing Sigma " + key);
        return value;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte item : hash) out.append(String.format(Locale.ROOT, "%02x", item));
            return out.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record ImportResult(Map<String, Object> spec, String ruleId,
                               List<String> selections, String condition) {
        public ImportResult {
            spec = Map.copyOf(spec);
            selections = List.copyOf(selections);
        }
    }

    private record ConditionPlan(List<String> required, List<String> any) {
        private ConditionPlan {
            required = List.copyOf(required);
            any = List.copyOf(any);
        }
    }
}
