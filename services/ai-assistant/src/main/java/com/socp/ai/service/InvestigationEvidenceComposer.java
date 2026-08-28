package com.socp.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure evidence-to-investigation projection, isolated from orchestration and persistence. */
final class InvestigationEvidenceComposer {

    private InvestigationEvidenceComposer() {
    }

    static String searchQuery(Map<String, Object> alert, List<Map<String, Object>> evidence) {
        List<String> terms = new ArrayList<>();
        for (Map<String, Object> item : evidence) {
            String eventId = text(item.get("eventId"));
            if (eventId != null) terms.add("eventId=" + eventId);
        }
        String entity = text(alert.get("entity"));
        if (entity != null) terms.add("host=" + entity);
        return terms.isEmpty() ? "" : String.join(" OR ", terms);
    }

    static List<Map<String, Object>> citations(String alertId, Map<String, Object> alert,
                                               List<Map<String, Object>> evidence,
                                               List<Map<String, Object>> related,
                                               List<Map<String, Object>> incidents,
                                               Map<String, Object> iocMatches) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("id", "alert:" + alertId, "source", "alert-web",
                "locator", "/alert-web/api/alarms/" + alertId, "description", "alert fact"));
        int index = 0;
        for (Map<String, Object> item : evidence) {
            String eventId = text(item.get("eventId"));
            if (eventId == null) continue;
            out.add(Map.of("id", "evidence:" + eventId, "source", "alert-web",
                    "locator", "/alert-web/api/alarms/" + alertId + "/evidence#" + eventId,
                    "description", "captured source event " + (++index)));
        }
        for (Map<String, Object> event : related) {
            String eventId = text(event.get("eventId"));
            if (eventId != null) out.add(Map.of("id", "search:" + eventId, "source", "search-config",
                    "locator", "/search-config/api/v1/search?eventId=" + eventId,
                    "description", "related original event"));
        }
        for (Map<String, Object> incident : incidents) {
            String id = text(incident.get("id"));
            if (id != null) out.add(Map.of("id", "incident:" + id, "source", "incident-web",
                    "locator", "/incident-web/api/v1/incidents/" + id, "description", "related incident"));
        }
        for (String value : iocMatches.keySet()) {
            out.add(Map.of("id", "ioc:" + value, "source", "threat-web",
                    "locator", "/threat-web/api/v1/iocs/match?value=" + value,
                    "description", "matched IOC"));
        }
        return out;
    }

    static List<String> iocValues(Map<String, Object> alert, List<Map<String, Object>> evidence) {
        Set<String> values = new LinkedHashSet<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        sources.add(alert);
        sources.addAll(evidence);
        for (Map<String, Object> source : sources) {
            for (String key : List.of("src_ip", "dst_ip", "sourceIp", "destinationIp",
                    "ip", "domain", "url", "sha256", "ioc", "iocValue")) {
                String value = text(source.get(key));
                if (value != null) values.add(value);
            }
            Object nested = source.get("iocs");
            if (nested instanceof List<?> list) {
                list.forEach(item -> {
                    String value = text(item);
                    if (value != null) values.add(value);
                });
            }
        }
        return new ArrayList<>(values);
    }

    static List<Map<String, Object>> timeline(Map<String, Object> alert,
                                              List<Map<String, Object>> evidence,
                                              List<Map<String, Object>> related) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(timelineItem(alert.get("occurredAt"), "ALERT", alert.get("message"),
                "alert:" + alert.get("id")));
        for (Map<String, Object> item : evidence) {
            out.add(timelineItem(item.get("timestamp"), "EVIDENCE", item.get("raw"),
                    "evidence:" + item.getOrDefault("eventId", "unknown")));
        }
        for (Map<String, Object> item : related) {
            Object message = item.containsKey("msg") ? item.get("msg") : item.get("message");
            out.add(timelineItem(item.get("timestamp"), "RELATED_EVENT", message,
                    "search:" + item.getOrDefault("eventId", "unknown")));
        }
        out.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("timestamp"))));
        return out;
    }

    static List<Map<String, Object>> hypotheses(Map<String, Object> alert,
                                                List<Map<String, Object>> citations,
                                                List<String> degraded) {
        String rule = text(alert.get("ruleId"));
        String entity = text(alert.get("entity"));
        String support = citations.stream().filter(item -> String.valueOf(item.get("id")).startsWith("evidence:"))
                .map(item -> String.valueOf(item.get("id"))).findFirst().orElse("alert:" + alert.get("id"));
        double confidence = degraded.isEmpty() ? 0.72 : 0.55;
        return List.of(Map.of("hypothesis", "The alert is consistent with "
                        + (rule == null ? "the detected behavior" : rule) + " affecting "
                        + (entity == null ? "the reported entity" : entity),
                "supporting", List.of(support),
                "contradicting", List.of("No contrary evidence was observed in the bounded query"),
                "confidence", confidence,
                "confidenceNote", "Confidence is bounded by captured evidence and is not a verdict"));
    }

    static List<Map<String, Object>> nextActions(String searchQuery) {
        return List.of(
                Map.of("type", "PRESERVE_EVIDENCE", "status", "RECOMMENDED",
                        "description", "Preserve the cited raw events and host timeline"),
                Map.of("type", "RUN_SPL", "status", "RECOMMENDED", "query", searchQuery),
                Map.of("type", "SOAR_SUGGESTION", "status", "REQUIRES_HUMAN_APPROVAL",
                        "executable", false,
                        "description", "Review containment or credential-reset actions before execution")
        );
    }

    static String deterministicAnalysis(Map<String, Object> alert, List<Map<String, Object>> evidence,
                                        List<Map<String, Object>> related, List<String> degraded) {
        return "Evidence-first assessment for alert " + alert.getOrDefault("id", "unknown")
                + ": captured=" + evidence.size() + ", relatedEvents=" + related.size()
                + ". This is an analyst aid, not an automatic containment decision."
                + (degraded.isEmpty() ? "" : " Sources unavailable: " + String.join(", ", degraded) + ".");
    }

    static String incidentSummary(Map<String, Object> result) {
        StringBuilder out = new StringBuilder("SOCP AI investigation summary\n");
        out.append("Alert: ").append(result.get("alertId")).append('\n');
        out.append("Assessment: ").append(result.get("analysis")).append('\n');
        out.append("Recommended SPL: ").append(result.get("recommendedSpl")).append('\n');
        out.append("Citations: ").append(result.get("citations"));
        return truncate(out.toString(), 8000);
    }

    private static Map<String, Object> timelineItem(Object timestamp, String type,
                                                    Object message, String citation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("timestamp", text(timestamp) == null ? "" : text(timestamp));
        item.put("type", type);
        item.put("message", truncate(text(message), 512));
        item.put("citation", citation);
        return item;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isBlank() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
