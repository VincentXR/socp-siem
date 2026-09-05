package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.soar.web.definition.SoarDefinitionValidator;
import com.socp.soar.web.definition.SoarExpressionEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Side-effect-free preview interpreter. It deliberately has no connector,
 * repository, clock or network dependency, so a draft can never accidentally
 * execute a response action while an analyst is testing a graph.
 */
public final class SoarDryRunEngine {
    private final ObjectMapper mapper;
    private final SoarDefinitionValidator validator;

    public SoarDryRunEngine(ObjectMapper mapper, SoarDefinitionValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public Map<String, Object> run(String definition, Map<String, Object> input,
                                   Map<String, Object> subject) {
        var checked = validator.validate(definition);
        if (!checked.valid()) {
            throw new IllegalArgumentException("SOAR_DEFINITION_INVALID: definition is not executable");
        }
        JsonNode root;
        try { root = mapper.readTree(definition); }
        catch (Exception failure) { throw new IllegalArgumentException("SOAR_DEFINITION_INVALID", failure); }

        Map<String, Object> variables = new LinkedHashMap<>();
        if (input != null) variables.putAll(input);
        variables.put("subject", subject == null ? Map.of() : new LinkedHashMap<>(subject));
        variables.put("trigger", new LinkedHashMap<>(variables));
        // Keep expression namespaces aligned with a real V2 Workflow while
        // making it explicit that this is a simulation and has no durable
        // run identity or side effect capability.
        variables.put("run", Map.of("id", "dry-run", "runId", "dry-run",
                "executionSeriesId", "dry-run", "simulation", true));
        List<Map<String, Object>> results = new ArrayList<>();
        ArrayDeque<String> path = new ArrayDeque<>();
        String current = root.path("entryNodeId").asText("");
        int steps = 0;
        while (current != null && !current.isBlank() && steps++ < 500) {
            JsonNode node = findNode(root.path("nodes"), current);
            if (node == null) break;
            String id = node.path("id").asText(current);
            String type = node.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodeId", id);
            result.put("nodeType", type);
            result.put("status", "SIMULATED");
            result.put("sideEffectsSuppressed", true);
            String branch = "success";
            switch (type) {
                case "START" -> result.put("output", Map.of("started", true));
                case "END" -> {
                    result.put("status", "SIMULATED");
                    result.put("output", Map.of("outcome", node.path("outcome").asText("SUCCEEDED")));
                    results.add(result);
                    current = null;
                    continue;
                }
                case "ACTION" -> {
                    String actionRef = node.path("actionRef").asText("");
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("status", "SIMULATED");
                    output.put("actionRef", actionRef);
                    output.put("sideEffectSuppressed", true);
                    result.put("actionRef", actionRef);
                    result.put("output", output);
                    variables.put("nodes." + id + ".output", output);
                }
                case "CONDITION" -> {
                    boolean matched = SoarExpressionEngine.evaluate(node.path("expression").asText(""), variables);
                    branch = matched ? "true" : "false";
                    result.put("matched", matched);
                }
                case "SWITCH" -> {
                    Object value = resolve(node.path("expression").asText(""), variables);
                    branch = switchBranch(node, value);
                    result.put("value", value);
                    result.put("port", branch);
                }
                case "SET_VARIABLE" -> {
                    String name = node.path("config").path("name").asText(node.path("id").asText(""));
                    if (!name.startsWith("vars.") && !"vars".equals(name)) {
                        result.put("status", "FAILED");
                        result.put("errorCode", "VARIABLE_SCOPE_INVALID");
                        result.put("warning", "SET_VARIABLE may only write vars.* during execution");
                        results.add(result);
                        current = null;
                        continue;
                    }
                    String key = "vars".equals(name) ? node.path("id").asText("value") : name.substring(5);
                    Object value = resolveValue(node.path("config").get("value"), variables);
                    variables.put(key, value);
                    result.put("output", Map.of("name", key, "value", value, "simulated", true));
                }
                case "PARALLEL" -> {
                    List<String> starts = outgoing(root, node).stream().map(SoarDryRunEngine::edgeTarget)
                            .filter(value -> value != null && !value.isBlank()).toList();
                    String join = commonJoin(root, starts);
                    result.put("output", Map.of("planned", true, "branches", starts.size(),
                            "joinNodeId", join == null ? "" : join));
                    if (join == null) {
                        result.put("status", "FAILED");
                        result.put("errorCode", "PARALLEL_JOIN_REQUIRED");
                        results.add(result);
                        current = null;
                        continue;
                    }
                    results.add(result);
                    current = join;
                    continue;
                }
                case "FOREACH" -> {
                    Object collection = resolve(node.path("config").path("itemsPath").asText(""), variables);
                    int count = collection instanceof List<?> list ? list.size() : 0;
                    result.put("output", Map.of("planned", true, "iterations", count,
                            "concurrency", node.path("limits").path("concurrency").asInt(1)));
                    result.put("warning", "FOREACH body is represented as a bounded plan; no item action is executed");
                    results.add(result);
                    current = nextNode(root.path("edges"), id, "done");
                    if (current == null) current = nextNode(root.path("edges"), id, "success");
                    continue;
                }
                case "JOIN", "DELAY", "SUB_PLAYBOOK" -> {
                    result.put("output", Map.of("planned", true));
                }
                case "APPROVAL", "MANUAL_TASK" -> {
                    result.put("output", Map.of("planned", true));
                    result.put("warning", "human interaction is not requested during dry-run");
                }
                default -> result.put("status", "FAILED");
            }
            results.add(result);
            current = nextNode(root.path("edges"), id, branch);
            if (current != null && path.size() < 3) path.addLast(current);
            if (current != null && path.contains(current) && !"FOREACH".equals(type)) break;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", "SIMULATED");
        output.put("mode", "DRY_RUN");
        output.put("sideEffectsSuppressed", true);
        output.put("definitionHash", checked.definitionHash());
        output.put("nodes", results);
        output.put("variables", redact(variables));
        output.put("steps", steps);
        output.put("warnings", List.of("No connector, HTTP, persistence or notification side effect was executed"));
        return output;
    }

    private Object resolve(String expression, Map<String, Object> variables) {
        String value = expression == null ? "" : expression.trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        if (variables.containsKey(value)) return variables.get(value);
        String normalized = value.replaceFirst("^(vars|variables)\\.", "");
        if (variables.containsKey(normalized)) return variables.get(normalized);
        String[] parts = normalized.split("\\.");
        for (int prefixLength = parts.length - 1; prefixLength > 0; prefixLength--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, prefixLength));
            if (!variables.containsKey(prefix)) continue;
            Object current = variables.get(prefix);
            for (int index = prefixLength; index < parts.length; index++) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(parts[index]);
            }
            return current;
        }
        Object current = variables;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current == null ? value : current;
    }

    private Object resolveValue(JsonNode value, Map<String, Object> variables) {
        if (value == null || value.isNull()) return null;
        if (value.isTextual() && value.asText().startsWith("$expr:")) {
            return resolve(value.asText().substring(6), variables);
        }
        if (value.isObject() && value.size() == 1 && value.has("$expr")) {
            return resolve(value.path("$expr").asText(""), variables);
        }
        return mapper.convertValue(value, Object.class);
    }

    private static String switchBranch(JsonNode node, Object value) {
        JsonNode cases = node.get("cases");
        if (cases == null || !cases.isArray()) cases = node.path("config").get("cases");
        if (cases != null && cases.isArray()) for (JsonNode item : cases) {
            JsonNode expected = item.has("value") ? item.get("value") : item.get("when");
            String port = item.path("port").asText(item.path("toPort").asText(""));
            if (expected != null && String.valueOf(value).equalsIgnoreCase(expected.asText()) && !port.isBlank()) return port;
        }
        return node.path("config").path("defaultPort").asText("default");
    }

    private static String nextNode(JsonNode edges, String from, String branch) {
        String fallback = null;
        if (!edges.isArray()) return null;
        for (JsonNode edge : edges) {
            if (!from.equals(edge.path("from").asText(edge.path("source").asText("")))) continue;
            String port = edge.path("port").asText(edge.path("when").asText(""));
            String to = edge.path("to").asText(edge.path("target").asText(""));
            if (fallback == null && (port.isBlank() || "default".equalsIgnoreCase(port))) fallback = to;
            if (branch.equalsIgnoreCase(port)) return to;
        }
        return fallback;
    }

    private static List<JsonNode> outgoing(JsonNode root, JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (root == null || node == null || !root.path("edges").isArray()) return result;
        String id = node.path("id").asText("");
        for (JsonNode edge : root.path("edges")) {
            String from = edge.path("from").asText(edge.path("source").asText(""));
            if (id.equals(from)) result.add(edge);
        }
        return result;
    }

    private static String edgeTarget(JsonNode edge) {
        return edge.path("to").asText(edge.path("target").asText(""));
    }

    private static String commonJoin(JsonNode root, List<String> starts) {
        if (starts == null || starts.size() < 2) return null;
        Set<String> candidates = null;
        for (String start : starts) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                if (!reachable.add(id)) continue;
                JsonNode node = findNode(root.path("nodes"), id);
                if (node != null && "JOIN".equalsIgnoreCase(node.path("type").asText(""))) continue;
                if (node != null) for (JsonNode edge : outgoing(root, node)) {
                    String target = edgeTarget(edge);
                    if (target != null && !target.isBlank()) queue.add(target);
                }
            }
            if (candidates == null) candidates = reachable;
            else candidates.retainAll(reachable);
        }
        if (candidates == null) return null;
        for (String id : candidates) {
            JsonNode node = findNode(root.path("nodes"), id);
            if (node != null && "JOIN".equalsIgnoreCase(node.path("type").asText(""))) return id;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String lower = key.toLowerCase(java.util.Locale.ROOT);
                result.put(key, lower.contains("secret") || lower.contains("token")
                        || lower.contains("password") || lower.contains("authorization")
                        || lower.equals("cookie") ? "[REDACTED]" : redact(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(SoarDryRunEngine::redact).toList();
        return value;
    }

    private static JsonNode findNode(JsonNode nodes, String id) {
        if (!nodes.isArray()) return null;
        for (JsonNode node : nodes) if (id.equals(node.path("id").asText())) return node;
        return null;
    }
}
