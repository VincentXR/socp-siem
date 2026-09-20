package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Identity and metadata contract for the independently routed detection stream.
 *
 * <p>{@link SecurityEvent#id()} remains the source evidence identity. A delivery
 * id names one source event routed for one execution class/dimension under one
 * routing version. Route-plan changes are observable through {@link #PLAN_VERSION_FIELD}
 * but deliberately do not change delivery identity, so a replay after a rule
 * reload cannot manufacture a second business event.</p>
 */
public final class DetectionDelivery {

    public static final String SCHEMA_VERSION = "detection-delivery-schema-v2";
    public static final String ROUTING_VERSION = "detection-routing-v2";

    public static final String DELIVERY_ID_FIELD = "detection_delivery_id";
    public static final String SOURCE_EVENT_ID_FIELD = "detection_source_event_id";
    public static final String KIND_FIELD = "detection_delivery_kind";
    public static final String DIMENSION_FIELD = "detection_delivery_dimension";
    public static final String VALUE_FIELD = "detection_delivery_value";
    public static final String ROUTING_VERSION_FIELD = "detection_routing_version";
    public static final String PLAN_VERSION_FIELD = "detection_route_plan_version";
    public static final String SCHEMA_VERSION_FIELD = "detection_delivery_schema";
    public static final String SOURCE_TOPIC_FIELD = "detection_source_topic";
    public static final String SOURCE_PARTITION_FIELD = "detection_source_partition";
    public static final String SOURCE_OFFSET_FIELD = "detection_source_offset";
    public static final String MISSING_DIMENSIONS_FIELD = "detection_missing_dimensions";

    public static final String STATELESS_DIMENSION = "_stateless";
    public static final String STATELESS_VALUE = "_once";

    private DetectionDelivery() {
    }

    public enum Kind {
        STATELESS,
        STATEFUL
    }

    public static boolean isRouted(SecurityEvent event) {
        return text(event, DELIVERY_ID_FIELD) != null
                && text(event, ROUTING_VERSION_FIELD) != null
                && text(event, KIND_FIELD) != null;
    }

    public static Kind kind(SecurityEvent event) {
        String value = text(event, KIND_FIELD);
        if (value == null) return null;
        try {
            return Kind.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String dimension(SecurityEvent event) {
        return text(event, DIMENSION_FIELD);
    }

    public static String deliveryId(SecurityEvent event) {
        String explicit = text(event, DELIVERY_ID_FIELD);
        if (explicit != null) return explicit;
        return legacyDeliveryId(event);
    }

    public static String sourceEventId(SecurityEvent event) {
        String explicit = text(event, SOURCE_EVENT_ID_FIELD);
        return explicit == null && event != null ? event.id() : explicit;
    }

    public static String routingVersion(SecurityEvent event) {
        String version = text(event, ROUTING_VERSION_FIELD);
        return version == null ? "legacy-v1" : version;
    }

    public static String sourceTopic(SecurityEvent event) {
        return text(event, SOURCE_TOPIC_FIELD);
    }

    public static Integer sourcePartition(SecurityEvent event) {
        return integer(event, SOURCE_PARTITION_FIELD);
    }

    public static Long sourceOffset(SecurityEvent event) {
        return longValue(event, SOURCE_OFFSET_FIELD);
    }

    public static String deliveryId(String tenantId, String sourceEventId,
                                    String routingVersion, Kind kind,
                                    String dimension, String value) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (sourceEventId == null || sourceEventId.isBlank()) throw new IllegalArgumentException("sourceEventId is required");
        if (routingVersion == null || routingVersion.isBlank()) throw new IllegalArgumentException("routingVersion is required");
        if (kind == null) throw new IllegalArgumentException("delivery kind is required");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("routing value is required");
        String tuple = tenantId + "\u0000" + sourceEventId + "\u0000" + routingVersion
                + "\u0000" + kind.name() + "\u0000" + dimension + "\u0000" + value;
        return UUID.nameUUIDFromBytes(tuple.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String legacyDeliveryId(SecurityEvent event) {
        if (event == null || event.id() == null || event.id().isBlank()) return "legacy:unknown";
        // Legacy had exactly one delivery per source event. Reusing that source
        // id keeps pre-v2 journal rows claim-compatible after migration.
        return event.id();
    }

    private static String text(SecurityEvent event, String name) {
        Map<String, String> fields = event == null ? null : event.fields();
        if (fields == null) return null;
        String value = fields.get(name);
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value)
                ? null : value.trim();
    }

    private static Integer integer(SecurityEvent event, String name) {
        String value = text(event, name);
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long longValue(SecurityEvent event, String name) {
        String value = text(event, name);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
