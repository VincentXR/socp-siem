package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.search.config.parser.CanonicalEvent;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.persistence.store.ParseRuleStore;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts one vendor/raw log line into the canonical search/detection event contract. */
@Component
public class IngestEventNormalizer {

    private final ParsePreviewService preview;
    private final ParseRuleStore parseRules;
    private final ReferenceSetStore referenceSets;
    private final ParserRegistry parsers;

    public IngestEventNormalizer(ParsePreviewService preview, ParseRuleStore parseRules,
                                 ReferenceSetStore referenceSets, ParserRegistry parsers) {
        this.preview = preview;
        this.parseRules = parseRules;
        this.referenceSets = referenceSets;
        this.parsers = parsers;
    }

    NormalizedEvent normalize(String line, String collectorHint) {
        Map<String, String> canonical = parsers.parse(line, collectorHint);
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, String> ecs = new LinkedHashMap<>();
        canonical.forEach((key, value) -> {
            if (key.contains(".")) ecs.put(key, value);
            else fields.put(key, value);
        });
        String rawLog = canonical.getOrDefault(CanonicalEvent.EVENT_MESSAGE, line);
        applyFallbackParseRule(rawLog, fields, ecs);
        bridge(fields, canonical);
        // A collector identity obtained from the authenticated request is
        // authoritative. Never let an untrusted body field relabel the
        // source or its per-collector accounting.
        if (collectorHint != null && !collectorHint.isBlank()) {
            fields.put("collector", collectorHint);
        }
        enrich(fields);

        String source = pick(fields, "source", "type", "app", "vendor");
        String category = canonical.get(CanonicalEvent.EVENT_CATEGORY);
        if ((source.isBlank() || "sshd".equalsIgnoreCase(source))
                && "authentication".equalsIgnoreCase(category)) source = "auth";
        String host = pick(fields, "host", "hostname", "device", CanonicalEvent.HOST_NAME);
        String severity = pick(fields, "severity", "level", CanonicalEvent.EVENT_SEVERITY);
        if (severity.isBlank()) severity = "INFO";
        String eventId = firstNonBlank(canonical.get("eventId"), canonical.get("event.id"),
                canonical.get("id"), fields.get("eventId"), fields.get("event_id"));
        if (eventId == null) eventId = java.util.UUID.randomUUID().toString();

        fields.putIfAbsent("tenant_id", tenant());
        Map<String, String> routingFields = stringValues(fields);
        fields.put("detection_routing_field", DetectionRoutingKey.field(source, host, routingFields));
        fields.put("detection_routing_value", DetectionRoutingKey.value(source, host, routingFields));

        SearchEvent event = new SearchEvent(eventId, parseTimestamp(pick(fields, "timestamp", "@timestamp", "time")),
                source.isBlank() ? "unknown" : source,
                host.isBlank() ? "unknown" : host,
                severity.toUpperCase(java.util.Locale.ROOT), rawLog,
                Map.copyOf(stringValues(fields)), Map.copyOf(ecs));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.eventId());
        payload.put("source", event.source());
        payload.put("host", event.host());
        payload.put("severity", event.severity());
        payload.put("msg", event.msg());
        payload.put("timestamp", event.timestamp().toString());
        payload.put("fields", fields);
        if (!ecs.isEmpty()) payload.put("ecs", ecs);
        return new NormalizedEvent(event, payload, collector(fields, collectorHint));
    }

    @SuppressWarnings("unchecked")
    private void applyFallbackParseRule(String rawLog, Map<String, Object> fields,
                                        Map<String, String> ecs) {
        if (fields.size() > 1 || ecs.size() > 1) return;
        for (var rule : parseRules.list()) {
            Map<String, Object> result = preview.preview(rule.id(), null, null, rawLog);
            if (!Boolean.TRUE.equals(result.get("matched"))) continue;
            Object extracted = result.get("fields");
            if (extracted instanceof Map<?, ?> values) {
                values.forEach((key, value) -> fields.put(String.valueOf(key), value));
            }
            return;
        }
    }

    private void enrich(Map<String, Object> fields) {
        for (String key : List.of("src_ip", "ip", "user", "host", "dst_ip")) {
            Object value = fields.get(key);
            if (value == null) continue;
            List<String> hits = referenceSets.matchedSets(String.valueOf(value));
            if (hits.isEmpty()) continue;
            fields.put("watchlist", String.join(",", hits));
            if (hits.stream().anyMatch(hit -> hit.contains("封禁"))) fields.put("blocked", "true");
            if (hits.stream().anyMatch(hit -> hit.contains("核心资产"))) fields.put("asset_critical", "true");
            if (hits.stream().anyMatch(hit -> hit.contains("关键人员"))) fields.put("user_vip", "true");
        }
    }

    private static void bridge(Map<String, Object> fields, Map<String, String> canonical) {
        putIfAbsent(fields, "src_ip", canonical.get(CanonicalEvent.SOURCE_IP));
        putIfAbsent(fields, "dst_ip", canonical.get(CanonicalEvent.DESTINATION_IP));
        putIfAbsent(fields, "src_port", canonical.get(CanonicalEvent.SOURCE_PORT));
        putIfAbsent(fields, "dst_port", canonical.get(CanonicalEvent.DESTINATION_PORT));
        putIfAbsent(fields, "user", canonical.get(CanonicalEvent.USER_NAME));
        putIfAbsent(fields, "host", canonical.get(CanonicalEvent.HOST_NAME));
        putIfAbsent(fields, "msg", canonical.get(CanonicalEvent.EVENT_MESSAGE));
        putIfAbsent(fields, "severity", canonical.get(CanonicalEvent.EVENT_SEVERITY));
        putIfAbsent(fields, "action", canonical.get(CanonicalEvent.EVENT_ACTION));
        putIfAbsent(fields, "category", canonical.get(CanonicalEvent.EVENT_CATEGORY));
        putIfAbsent(fields, "process", canonical.get(CanonicalEvent.PROCESS_NAME));
        putIfAbsent(fields, "pid", canonical.get(CanonicalEvent.PROCESS_PID));
    }

    private static void putIfAbsent(Map<String, Object> values, String key, Object value) {
        if (value != null && values.get(key) == null) values.put(key, value);
    }

    private static Map<String, String> stringValues(Map<String, Object> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (value != null) values.put(key, String.valueOf(value));
        });
        return values;
    }

    private static String pick(Map<String, Object> fields, String... keys) {
        for (String key : keys) {
            if (fields.get(key) != null) return String.valueOf(fields.get(key));
        }
        return "";
    }

    private static String firstNonBlank(Object... values) {
        for (Object candidate : values) {
            if (candidate == null) continue;
            String value = String.valueOf(candidate);
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) return value;
        }
        return null;
    }

    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) return Instant.now();
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException notInstant) {
            try {
                return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value));
            } catch (java.time.DateTimeException invalid) {
                return Instant.now();
            }
        }
    }

    private static String collector(Map<String, Object> fields, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        Object collector = fields.get("collector");
        return collector == null || String.valueOf(collector).isBlank()
                ? "unknown" : String.valueOf(collector);
    }

    private static String tenant() {
        return TenantContext.require();
    }

    record NormalizedEvent(SearchEvent event, Map<String, Object> payload, String collector) {
    }
}
