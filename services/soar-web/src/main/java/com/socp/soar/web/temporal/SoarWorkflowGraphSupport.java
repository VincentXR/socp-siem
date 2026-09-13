package com.socp.soar.web.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, replay-safe helpers used by the SOAR graph interpreter.  Keeping
 * graph navigation, bounded snapshots and deterministic identities outside the
 * workflow state machine makes those rules independently reviewable without
 * moving Temporal calls into a non-deterministic service.
 */
public final class SoarWorkflowGraphSupport {

    private static final int MAX_WORKFLOW_ID_LENGTH = 240;
    private static final int MAX_PATH_LENGTH = 512;

    private SoarWorkflowGraphSupport() {
    }

    public static JsonNode findNode(JsonNode nodes, String id) {
        if (nodes == null || !nodes.isArray()) return null;
        for (JsonNode node : nodes) {
            if (id.equals(node.path("id").asText())) return node;
        }
        return null;
    }

    public static List<JsonNode> outgoing(JsonNode root, JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || root == null) return out;
        String source = node.path("id").asText();
        for (JsonNode edge : root.path("edges")) {
            if (source.equals(edgeText(edge, "from", "source"))) out.add(edge);
        }
        return out;
    }

    public static String edgeTo(JsonNode edge) {
        return edgeText(edge, "to", "target");
    }

    public static String edgeForPort(JsonNode root, String source, String... ports) {
        for (String port : ports) {
            String found = nextNode(root, source, port);
            if (found != null) return found;
        }
        return nextNode(root, source, "success");
    }

    public static String nextNode(JsonNode root, String source, String branch) {
        String fallback = null;
        if (root == null) return null;
        for (JsonNode edge : root.path("edges")) {
            if (!source.equals(edgeText(edge, "from", "source"))) continue;
            String port = edgeText(edge, "port", "when");
            String to = edgeTo(edge);
            if (fallback == null && (port.isBlank() || "default".equalsIgnoreCase(port))) fallback = to;
            if (branch.equalsIgnoreCase(port)) return to;
        }
        return fallback;
    }

    public static String commonJoin(JsonNode root, List<String> starts) {
        if (starts == null || starts.isEmpty()) return null;
        Set<String> candidates = null;
        for (String start : starts) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                if (!reachable.add(id)) continue;
                JsonNode node = findNode(root == null ? null : root.path("nodes"), id);
                if (node != null && "JOIN".equalsIgnoreCase(node.path("type").asText())) break;
                if (node != null) {
                    for (JsonNode edge : outgoing(root, node)) {
                        String to = edgeTo(edge);
                        if (to != null && !to.isBlank()) queue.add(to);
                    }
                }
            }
            if (candidates == null) candidates = reachable;
            else candidates.retainAll(reachable);
        }
        return candidates == null ? null : candidates.stream()
                .filter(id -> {
                    JsonNode node = findNode(root == null ? null : root.path("nodes"), id);
                    return node != null && "JOIN".equalsIgnoreCase(node.path("type").asText());
                })
                .sorted()
                .findFirst()
                .orElse(null);
    }

    public static String edgeText(JsonNode node, String first, String second) {
        String value = node == null ? "" : node.path(first).asText("");
        return value.isBlank() && node != null ? node.path(second).asText("") : value;
    }

    public static Object redactForConnector(String key, Object value) {
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("authorization") || lower.equals("cookie")) {
            return "[REDACTED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : map.entrySet()) {
                String childKey = String.valueOf(item.getKey());
                out.put(childKey, redactForConnector(childKey, item.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) out.add(redactForConnector("", item));
            return out;
        }
        return value;
    }

    public static Map<String, Object> snapshotVariables(Map<String, Object> variables, ObjectMapper mapper,
                                                 int maxBytes, int maxEntryBytes) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        int used = 2;
        if (variables == null) return snapshot;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            Object safe = redactForConnector(entry.getKey(), entry.getValue());
            String encoded;
            try {
                encoded = mapper.writeValueAsString(safe);
            } catch (Exception ignored) {
                encoded = "null";
            }
            int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxEntryBytes || used + bytes > maxBytes) {
                snapshot.put(entry.getKey(), Map.of("truncated", true, "originalBytes", bytes));
                used += 48;
            } else {
                snapshot.put(entry.getKey(), safe);
                used += bytes;
            }
        }
        return snapshot;
    }

    public static String branchWorkflowId(String runId, String suffix, String iterationPath) {
        String path = iterationPath == null || iterationPath.isBlank()
                ? "root" : iterationPath.replace('/', '-');
        return boundedWorkflowId("soar-branch-" + runId + "-" + suffix + "-" + path);
    }

    public static String childWorkflowId(String runId, String nodeId, String iterationPath) {
        String path = iterationPath == null || iterationPath.isBlank()
                ? "root" : iterationPath.replace('/', '-');
        return boundedWorkflowId("soar-child-" + runId + "-" + nodeId + "-" + path);
    }

    private static String boundedWorkflowId(String id) {
        if (id.length() <= MAX_WORKFLOW_ID_LENGTH) return id;
        String hash = Integer.toUnsignedString(id.hashCode(), 16);
        int keep = Math.max(1, MAX_WORKFLOW_ID_LENGTH - hash.length() - 1);
        return id.substring(0, keep) + "-" + hash;
    }

    public static String subPlaybookPath(String nodeId, String parentPath) {
        String prefix = parentPath == null || parentPath.isBlank() ? "" : parentPath + "/";
        String raw = prefix + "sub-" + nodeId;
        if (raw.length() <= MAX_PATH_LENGTH) return raw;
        String hash = Integer.toUnsignedString(raw.hashCode(), 16);
        int keep = Math.max(1, MAX_PATH_LENGTH - hash.length() - 1);
        return raw.substring(0, keep) + "-" + hash;
    }

    public static String branchPath(String parentPath, int index) {
        String suffix = String.valueOf(index);
        return parentPath == null || parentPath.isBlank() ? suffix : parentPath + "/" + suffix;
    }

    public static String idempotency(String tenant, String run, String series, String nodeId, String path) {
        String effectiveSeries = series == null || series.isBlank() ? run : series;
        String raw = effectiveSeries + ":" + nodeId + ":" + (path == null ? "" : path);
        if (raw.length() <= 240) return raw;
        String hash = deterministicHash(raw);
        int keep = Math.max(1, 240 - hash.length() - 1);
        return raw.substring(0, keep) + ":" + hash;
    }

    public static String actionIdempotency(String tenant, String run, String series, String nodeId,
                                    String path, JsonNode targetNode) {
        return sha256Hex(idempotencyParts(tenant, run, series, nodeId, path, targetNode, null));
    }

    public static String compensationIdempotency(String tenant, String run, String series, String nodeId,
                                          String path, String compensationRef, JsonNode targetNode) {
        return sha256Hex(idempotencyParts(tenant, run, series, nodeId, path, targetNode,
                "compensate:" + (compensationRef == null ? "" : compensationRef)));
    }

    private static String idempotencyParts(String tenant, String run, String series, String nodeId,
                                           String path, JsonNode targetNode, String suffix) {
        String effectiveSeries = series == null || series.isBlank() ? run : series;
        String target = targetNode == null || targetNode.isNull() || targetNode.isMissingNode()
                ? "" : targetNode.toString();
        StringBuilder parts = new StringBuilder();
        parts.append(tenant == null ? "" : tenant).append('\n').append(effectiveSeries).append('\n')
                .append(nodeId).append('\n').append(path == null ? "" : path).append('\n').append(target);
        if (suffix != null) parts.append('\n').append(suffix);
        return parts.toString();
    }

    public static String deterministicHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int index = 0; index < 8; index++) out.append(String.format("%02x", digest[index]));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
