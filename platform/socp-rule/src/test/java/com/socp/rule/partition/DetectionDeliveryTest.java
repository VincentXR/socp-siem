package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DetectionDeliveryTest {
    @Test
    void acceptsBothExecutionClassesButRejectsCrossTenantIdentityReuse() {
        for (DetectionDelivery.Kind kind : DetectionDelivery.Kind.values()) {
            Map<String, String> fields = envelope(kind);
            assertDoesNotThrow(() -> DetectionDelivery.validate(event(fields)));
            fields.put("tenant_id", "tenant-b");
            assertThrows(IllegalArgumentException.class, () -> DetectionDelivery.validate(event(fields)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "detection_delivery_schema", "detection_routing_version", "detection_delivery_kind",
            "detection_delivery_dimension", "detection_delivery_value", "detection_route_plan_version",
            "detection_source_event_id", "detection_source_topic", "detection_source_partition",
            "detection_source_offset", "detection_delivery_id", "detection_routing_field", "detection_routing_value"
    })
    void rejectsMissingControlFields(String field) {
        Map<String, String> fields = envelope(DetectionDelivery.Kind.STATEFUL);
        fields.remove(field);
        assertThrows(IllegalArgumentException.class, () -> DetectionDelivery.validate(event(fields)), field);
    }

    @ParameterizedTest
    @CsvSource({
            "detection_delivery_schema, future-schema", "detection_routing_version, legacy-v1",
            "detection_delivery_kind, unknown", "detection_source_event_id, other-event",
            "detection_source_partition, -1", "detection_source_partition, invalid",
            "detection_source_offset, -1", "detection_source_offset, invalid",
            "detection_delivery_dimension, invalid|dimension", "detection_delivery_id, invented-id",
            "detection_routing_field, src_ip", "detection_routing_value, another-user"
    })
    void rejectsMalformedOrInconsistentEnvelope(String field, String value) {
        Map<String, String> fields = envelope(DetectionDelivery.Kind.STATEFUL);
        fields.put(field, value);
        assertThrows(IllegalArgumentException.class, () -> DetectionDelivery.validate(event(fields)), field);
    }

    @Test
    void statelessDeliveryCannotSelectAnEntityDimensionOrValue() {
        Map<String, String> fields = envelope(DetectionDelivery.Kind.STATELESS);
        fields.put(DetectionDelivery.DIMENSION_FIELD, "user");
        assertThrows(IllegalArgumentException.class, () -> DetectionDelivery.validate(event(fields)));
        fields.put(DetectionDelivery.DIMENSION_FIELD, DetectionDelivery.STATELESS_DIMENSION);
        fields.put(DetectionDelivery.VALUE_FIELD, "alice");
        assertThrows(IllegalArgumentException.class, () -> DetectionDelivery.validate(event(fields)));
    }

    @Test
    void quarantinesAllReservedInputWithoutOverwritingEvidenceAndIsIdempotent() {
        Map<String, String> fields = new HashMap<>(Map.of(
                "detection_delivery_id", "untrusted",
                "user_payload.detection_delivery_id", "existing",
                "user_payload.user_payload.detection_delivery_id", "nested-existing",
                "detection_future_control", "future",
                "routing_field", "user", "routing_value", "alice", "msg", "original"));
        DetectionDelivery.quarantineInputMetadata(fields);
        assertFalse(fields.containsKey("detection_delivery_id"));
        assertFalse(fields.containsKey("routing_field"));
        assertFalse(fields.containsKey("routing_value"));
        assertEquals("untrusted", fields.get("user_payload.user_payload.user_payload.detection_delivery_id"));
        assertEquals("existing", fields.get("user_payload.detection_delivery_id"));
        assertEquals("nested-existing", fields.get("user_payload.user_payload.detection_delivery_id"));
        assertEquals("future", fields.get("user_payload.detection_future_control"));
        assertEquals("user", fields.get("user_payload.routing_field"));
        assertEquals("alice", fields.get("user_payload.routing_value"));
        assertEquals("original", fields.get("msg"));
        Map<String, String> once = Map.copyOf(fields);
        DetectionDelivery.quarantineInputMetadata(fields);
        DetectionDelivery.quarantineField(fields, "absent");
        assertEquals(once, fields);
    }

    private static Map<String, String> envelope(DetectionDelivery.Kind kind) {
        String dimension = kind == DetectionDelivery.Kind.STATELESS ? DetectionDelivery.STATELESS_DIMENSION : "user";
        String value = kind == DetectionDelivery.Kind.STATELESS ? DetectionDelivery.STATELESS_VALUE : "alice";
        Map<String, String> fields = new HashMap<>();
        fields.put("tenant_id", "tenant-a");
        fields.put(DetectionDelivery.SCHEMA_VERSION_FIELD, DetectionDelivery.SCHEMA_VERSION);
        fields.put(DetectionDelivery.ROUTING_VERSION_FIELD, DetectionDelivery.ROUTING_VERSION);
        fields.put(DetectionDelivery.KIND_FIELD, kind.name());
        fields.put(DetectionDelivery.DIMENSION_FIELD, dimension);
        fields.put(DetectionDelivery.VALUE_FIELD, value);
        fields.put(DetectionDelivery.PLAN_VERSION_FIELD, "plan-a");
        fields.put(DetectionDelivery.SOURCE_EVENT_ID_FIELD, "event-1");
        fields.put(DetectionDelivery.SOURCE_TOPIC_FIELD, "socp-events");
        fields.put(DetectionDelivery.SOURCE_PARTITION_FIELD, "0");
        fields.put(DetectionDelivery.SOURCE_OFFSET_FIELD, "10");
        fields.put(DetectionRoutingKey.ROUTING_FIELD, dimension);
        fields.put(DetectionRoutingKey.ROUTING_VALUE, value);
        fields.put(DetectionDelivery.DELIVERY_ID_FIELD, DetectionDelivery.deliveryId(
                "tenant-a", "event-1", DetectionDelivery.ROUTING_VERSION, kind, dimension, value));
        return fields;
    }

    private static SecurityEvent event(Map<String, String> fields) {
        return new SecurityEvent("event-1", Instant.parse("2026-09-20T00:00:00Z"),
                "auth", "host-1", "evidence", fields, Severity.INFO);
    }
}
