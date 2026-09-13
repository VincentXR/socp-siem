package com.socp.soar.web.definition;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.soar.web.domain.DefinitionIssue;
import com.socp.soar.web.domain.SoarNodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Graph-specific checks for the deterministic SOAR workflow subset. */
final class SoarGraphValidator {

    private SoarGraphValidator() {
    }

    static boolean hasOutgoingPort(JsonNode edges, String nodeId, Set<String> expected) {
        if (edges == null || !edges.isArray()) return false;
        for (JsonNode edge : edges) {
            if (!nodeId.equals(text(edge, "from"))) continue;
            String port = text(edge, "port");
            if (port.isBlank()) port = text(edge, "when");
            if (expected.contains(port.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Keep edge labels aligned with branch names understood by the runtime. */
    static void validateEdgePort(SoarNodeType type, JsonNode node,
                                 String port, String nodeId, String edgePath,
                                 List<DefinitionIssue> errors) {
        String normalized = port == null ? "" : port.trim().toLowerCase(Locale.ROOT);
        boolean valid;
        switch (type) {
            case START, END -> valid = normalized.isBlank();
            case ACTION -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure", "error", "unknown").contains(normalized);
            case CONDITION -> valid = Set.of("true", "false").contains(normalized);
            case SWITCH -> valid = switchPortDeclared(node, normalized);
            case PARALLEL -> valid = normalized.isBlank() || normalized.equals("default")
                    || normalized.matches("[a-z][a-z0-9_.-]{0,31}");
            case JOIN -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure", "error").contains(normalized);
            case FOREACH -> valid = normalized.isBlank() || Set.of(
                    "default", "body", "each", "done", "success").contains(normalized);
            case DELAY, SET_VARIABLE -> valid = normalized.isBlank()
                    || Set.of("default", "success").contains(normalized);
            case APPROVAL -> valid = Set.of("approved", "rejected").contains(normalized);
            case MANUAL_TASK -> valid = normalized.isBlank() || Set.of(
                    "default", "completed", "success", "timeout").contains(normalized);
            case SUB_PLAYBOOK -> valid = normalized.isBlank() || Set.of(
                    "default", "success", "failure").contains(normalized);
            default -> valid = false;
        }
        if (!valid) {
            errors.add(DefinitionIssue.error("EDGE_PORT_NOT_ALLOWED", nodeId,
                    edgePath + "/port", "edge port '" + (port == null ? "" : port)
                            + "' is not valid for " + type.name()));
        }
    }

    static Set<String> reachable(Map<String, List<String>> graph, String start) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (String next : graph.getOrDefault(current, List.of())) queue.addLast(next);
        }
        return visited;
    }

    static Set<String> reverseReachable(Map<String, List<String>> graph, Set<String> ends) {
        Map<String, List<String>> reverse = new HashMap<>();
        graph.forEach((from, targets) -> targets.forEach(to ->
                reverse.computeIfAbsent(to, ignored -> new ArrayList<>()).add(from)));
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(ends);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            for (String previous : reverse.getOrDefault(current, List.of())) queue.addLast(previous);
        }
        return visited;
    }

    static boolean hasUnboundedCycle(Map<String, List<String>> graph,
                                     Map<String, SoarNodeType> types) {
        Map<String, Integer> stackIndex = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String node : types.keySet()) {
            if (unboundedCycle(graph, types, node, stackIndex, stack)) return true;
        }
        return false;
    }

    private static boolean unboundedCycle(Map<String, List<String>> graph,
                                         Map<String, SoarNodeType> types,
                                         String node,
                                         Map<String, Integer> stackIndex,
                                         List<String> stack) {
        Integer existing = stackIndex.get(node);
        if (existing != null) {
            for (int index = existing; index < stack.size(); index++) {
                if (types.get(stack.get(index)) == SoarNodeType.FOREACH) return false;
            }
            return true;
        }
        stackIndex.put(node, stack.size());
        stack.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            if (unboundedCycle(graph, types, next, stackIndex, stack)) return true;
        }
        stack.remove(stack.size() - 1);
        stackIndex.remove(node);
        return false;
    }

    private static boolean switchPortDeclared(JsonNode node, String port) {
        if (port.isBlank() || "default".equals(port)) return true;
        JsonNode cases = node == null ? null : node.get("cases");
        if (cases == null || !cases.isArray()) {
            cases = node == null ? null : node.path("config").get("cases");
        }
        if (cases == null || !cases.isArray()) return false;
        for (JsonNode item : cases) {
            if (item == null || !item.isObject()) continue;
            String declared = text(item, "port");
            if (declared.isBlank()) declared = text(item, "toPort");
            if (port.equalsIgnoreCase(declared)) return true;
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
