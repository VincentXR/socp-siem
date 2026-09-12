package com.socp.search.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.search.config.domain.SearchEvent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonical identity helpers for the durable ingest boundary. */
public final class IngestionEventIdentity {

    private static final Set<String> TRANSIENT_FIELDS = Set.of("ingested_at", "event_time_generated");
    private static final String GENERATED_EVENT_TIME = "true";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private IngestionEventIdentity() {
    }

    /**
     * Fingerprints event content without the producer identity.  This lets the
     * commit boundary distinguish a safe retry from identity reuse with a new
     * payload while keeping identical raw lines as separate events when their
     * event IDs differ.
     */
    public static String fingerprint(SearchEvent event) {
        if (event == null) throw new IllegalArgumentException("event is required");
        Map<String, Object> content = new LinkedHashMap<>();
        boolean generatedEventTime = event.fields() != null
                && GENERATED_EVENT_TIME.equalsIgnoreCase(event.fields().get("event_time_generated"));
        if (!generatedEventTime) {
            content.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        }
        content.put("source", event.source());
        content.put("host", event.host());
        content.put("severity", event.severity());
        content.put("msg", event.msg());
        content.put("fields", sorted(event.fields()));
        content.put("ecs", sorted(event.ecs()));
        try {
            byte[] canonical = MAPPER.writeValueAsBytes(content);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (Exception serializationFailure) {
            throw new IllegalArgumentException("Cannot fingerprint event " + event.eventId(),
                    serializationFailure);
        }
    }

    /**
     * Computes the same content identity from an already persisted outbox
     * payload.  Outbox rows written by older versions are allowed to have a
     * different JSON map order, but they must still describe the same event.
     * Invalid payloads deliberately return {@code null} so the commit boundary
     * can fail closed instead of treating corrupt data as a safe retry.
     */
    public static String fingerprintPayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            return fingerprint(MAPPER.readValue(payload, SearchEvent.class));
        } catch (Exception invalidPayload) {
            return null;
        }
    }

    private static Map<String, String> sorted(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            if (!TRANSIENT_FIELDS.contains(key)) sorted.put(key, value);
        });
        return sorted;
    }
}
