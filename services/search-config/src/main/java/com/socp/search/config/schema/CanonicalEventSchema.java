package com.socp.search.config.schema;

import com.socp.platform.tenant.context.TenantContext;

import java.time.Instant;
import java.util.Map;

/** Version gate for canonical events crossing the Kafka boundary. */
public final class CanonicalEventSchema {
    public static final String CURRENT = "1.0";

    private CanonicalEventSchema() {
    }

    /** Missing versions are accepted for the first rolling upgrade. */
    public static String effectiveVersion(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return CURRENT;
        return String.valueOf(value).trim();
    }

    public static boolean supports(Object value) {
        return CURRENT.equals(effectiveVersion(value));
    }

    public static void requireSupported(Map<String, Object> envelope) {
        if (envelope == null || !supports(envelope.get("schemaVersion"))) {
            Object actual = envelope == null ? null : envelope.get("schemaVersion");
            throw new UnsupportedSchemaVersionException(effectiveVersion(actual));
        }
        // A missing version is the deliberately supported legacy rolling
        // upgrade format.  Once a producer opts into the versioned envelope,
        // validate its required boundary fields before it can reach storage.
        if (envelope.get("schemaVersion") != null
                && !String.valueOf(envelope.get("schemaVersion")).isBlank()) {
            validateVersionedEnvelope(envelope);
        }
    }

    private static void validateVersionedEnvelope(Map<String, Object> envelope) {
        requireText(envelope, "eventId");
        String tenant = requireText(envelope, "tenantId");
        if (!TenantContext.isValid(tenant)) {
            throw new SchemaValidationException("tenantId is invalid");
        }
        String timestamp = requireText(envelope, "timestamp");
        try {
            Instant.parse(timestamp);
        } catch (Exception invalid) {
            throw new SchemaValidationException("timestamp must be an ISO-8601 instant");
        }
        requireText(envelope, "source");
        requireText(envelope, "host");
        String severity = requireText(envelope, "severity").toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)) {
            throw new SchemaValidationException("severity is not supported: " + severity);
        }
        requireText(envelope, "msg");
        if (!(envelope.get("fields") instanceof Map<?, ?>)) {
            throw new SchemaValidationException("fields must be an object");
        }
    }

    private static String requireText(Map<String, Object> envelope, String field) {
        Object value = envelope.get(field);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new SchemaValidationException(field + " is required");
        }
        return String.valueOf(value);
    }

    public static final class UnsupportedSchemaVersionException extends IllegalArgumentException {
        public UnsupportedSchemaVersionException(String version) {
            super("unsupported canonical event schemaVersion: " + version);
        }
    }

    public static final class SchemaValidationException extends IllegalArgumentException {
        public SchemaValidationException(String message) {
            super("invalid canonical event envelope: " + message);
        }
    }
}
