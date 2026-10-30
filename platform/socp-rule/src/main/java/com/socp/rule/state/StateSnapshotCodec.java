package com.socp.rule.state;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.util.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small JSON codec shared by stateful rules; event timestamps stay portable strings. */
public final class StateSnapshotCodec {

    private static final int MAX_CACHED_EVENTS = 4_096;
    private static final long MAX_CACHED_EVENT_BYTES = 8L * 1024 * 1024;
    private static final Map<EventIdentity, EncodedEvent> EVENTS = new LinkedHashMap<>(16, 0.75f, true);
    private static long eventCacheBytes;

    private record EncodedEvent(CachedEvidence json, long bytes) { }

    /** Cache immutable instances without repeatedly hashing their entire field map. */
    private record EventIdentity(SecurityEvent event) {
        @Override public int hashCode() { return System.identityHashCode(event); }
        @Override public boolean equals(Object other) {
            return other instanceof EventIdentity identity && event == identity.event;
        }
    }

    /** Preserve the public Map contract while writing the already escaped immutable JSON. */
    private static final class CachedEvidence extends java.util.AbstractMap<String, Object>
            implements com.fasterxml.jackson.databind.JsonSerializable {
        private final Map<String, Object> fields;
        private final String json;
        private final byte[] fingerprint;
        private CachedEvidence(Map<String, Object> fields, String json) {
            this.fields = java.util.Collections.unmodifiableMap(fields);
            this.json = json;
            this.fingerprint = fingerprint(fields);
        }
        @Override public java.util.Set<Entry<String, Object>> entrySet() { return fields.entrySet(); }
        @Override public void serialize(com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
            generator.writeRawValue(json);
        }
        @Override public void serializeWithType(com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider provider,
                com.fasterxml.jackson.databind.jsontype.TypeSerializer types) throws java.io.IOException {
            serialize(generator, provider);
        }
    }

    private StateSnapshotCodec() {
    }

    public static byte[] write(Object value) {
        try {
            return Json.mapper().writeValueAsBytes(value);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to encode rule state snapshot", failure);
        }
    }

    /** Detached undo tree: mutable containers are copied, immutable evidence is shared. */
    static Object freeze(Object value) {
        if (value instanceof CachedEvidence) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), freeze(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof java.util.Collection<?> items) {
            return items.stream().map(StateSnapshotCodec::freeze).toList();
        }
        if (value != null && value.getClass().isArray()) {
            var copy = new java.util.ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                copy.add(freeze(java.lang.reflect.Array.get(value, i)));
            }
            return java.util.Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("unsupported undo state value: " + value.getClass().getName());
    }

    /**
     * Domain-separated Merkle digest. Cached evidence and its decoded JSON map
     * have identical digests; large immutable payloads are hashed only once.
     * Map keys are sorted, list order is significant, and null is distinct from
     * an absent key. This is explicitly a new audit scope, not a JSON-byte hash.
     */
    static byte[] fingerprint(Object value) {
        if (value instanceof CachedEvidence evidence) return evidence.fingerprint;
        if (value != null && value.getClass().isArray()) return fingerprint(freeze(value));
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (value instanceof Map<?, ?> map) {
            digest.update((byte) 'M');
            var sorted = new java.util.TreeMap<String, Object>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            sorted.forEach((key, item) -> { digest.update(fingerprint(key)); digest.update(fingerprint(item)); });
        } else if (value instanceof java.util.Collection<?> items) {
            digest.update((byte) 'L');
            items.forEach(item -> digest.update(fingerprint(item)));
        } else {
            digest.update((byte) 'V');
            digest.update(write(value));
        }
        return digest.digest();
    }

    /** Conservative retained-tree estimate, including shared evidence for the undo budget. */
    static long retainedBytes(Object value) {
        if (value instanceof CachedEvidence evidence) return 1_024L + 4L * evidence.json.length();
        if (value instanceof Map<?, ?> map) return 128L + map.entrySet().stream()
                .mapToLong(entry -> 64L + retainedBytes(entry.getKey()) + retainedBytes(entry.getValue())).sum();
        if (value instanceof java.util.Collection<?> items) return 32L + items.stream()
                .mapToLong(item -> 8L + retainedBytes(item)).sum();
        return value instanceof String text ? 40L + 2L * text.length() : 24L;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(byte[] value) {
        if (value == null || value.length == 0) return Map.of();
        try {
            Object decoded = Json.mapper().readValue(value, Object.class);
            return decoded instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid rule state snapshot", failure);
        }
    }

    /** Reuse immutable evidence JSON without changing the portable checkpoint representation. */
    public static Map<String, Object> event(SecurityEvent event) {
        if (event == null) return Map.of();
        EventIdentity identity = new EventIdentity(event);
        synchronized (EVENTS) {
            EncodedEvent cached = EVENTS.get(identity);
            if (cached != null) return cached.json();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.id());
        out.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
        out.put("source", event.source());
        out.put("host", event.host());
        out.put("raw", event.raw());
        out.put("fields", event.fields() == null ? Map.of() : event.fields());
        out.put("severity", event.severity() == null ? Severity.INFO.name() : event.severity().name());
        String json;
        try { json = Json.mapper().writeValueAsString(out); }
        catch (Exception failure) { throw new IllegalStateException("unable to encode state evidence", failure); }
        var encoded = new EncodedEvent(new CachedEvidence(out, json),
                1_024L + 4L * json.length());
        synchronized (EVENTS) {
            EncodedEvent cached = EVENTS.get(identity);
            if (cached != null) return cached.json();
            if (encoded.bytes() <= MAX_CACHED_EVENT_BYTES) {
                EVENTS.put(identity, encoded);
                eventCacheBytes += encoded.bytes();
                var oldest = EVENTS.entrySet().iterator();
                while (EVENTS.size() > MAX_CACHED_EVENTS || eventCacheBytes > MAX_CACHED_EVENT_BYTES) {
                    eventCacheBytes -= oldest.next().getValue().bytes();
                    oldest.remove();
                }
            }
        }
        return encoded.json();
    }

    static long cachedEventBytes() { synchronized (EVENTS) { return eventCacheBytes; } }
    static int cachedEvents() { synchronized (EVENTS) { return EVENTS.size(); } }

    @SuppressWarnings("unchecked")
    public static SecurityEvent event(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = (Map<String, Object>) raw;
        Instant timestamp;
        try {
            timestamp = Instant.parse(String.valueOf(map.getOrDefault("timestamp", Instant.EPOCH)));
        } catch (Exception ignored) {
            timestamp = Instant.EPOCH;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        Object rawFields = map.get("fields");
        if (rawFields instanceof Map<?, ?> values) {
            values.forEach((key, item) -> fields.put(String.valueOf(key), String.valueOf(item)));
        }
        Severity severity;
        try {
            severity = Severity.valueOf(String.valueOf(map.getOrDefault("severity", "INFO")));
        } catch (Exception ignored) {
            severity = Severity.INFO;
        }
        return new SecurityEvent(String.valueOf(map.getOrDefault("id", "snapshot-event")), timestamp,
                String.valueOf(map.getOrDefault("source", "unknown")),
                String.valueOf(map.getOrDefault("host", "unknown")),
                String.valueOf(map.getOrDefault("raw", "")), fields, severity);
    }
}
