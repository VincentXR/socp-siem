package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical Kafka key contract for stateful detection.
 *
 * <p>The key deliberately contains the tenant, entity field and entity value.
 * That keeps two different entity dimensions with the same value (for example
 * a user named {@code web-1} and a host named {@code web-1}) from sharing a
 * window. Stateful rules are partition-local when their {@code keyField}
 * matches the event's {@code detection_routing_field}. Rules using another
 * entity dimension require an explicit fan-out/topic design and are not
 * claimed to be strictly partition-local by this contract.</p>
 */
public final class DetectionRoutingKey {

    /** Bump when the canonical event-key dimension or encoding changes. */
    public static final String VERSION = "detection-routing-v1";
    public static final String TENANT_FIELD = "tenant_id";
    public static final String ROUTING_FIELD = "detection_routing_field";
    public static final String ROUTING_VALUE = "detection_routing_value";

    /** Sources whose own host is the entity the window is about. */
    private static final Set<String> ENDPOINT_SOURCES = Set.of("edr", "auditd", "falco", "linux");

    private DetectionRoutingKey() {
    }

    public static String forEvent(SecurityEvent event) {
        if (event == null) return "default|unknown|unknown";
        String tenant = first(event.fields(), TENANT_FIELD, "tenantId", "tenant");
        String field = first(event.fields(), ROUTING_FIELD, "routing_field");
        String value = first(event.fields(), ROUTING_VALUE, "routing_value");
        if (field == null || value == null) {
            Entity entity = resolve(tenant, event.source(), event.host(), event.fields());
            tenant = entity.tenant();
            field = entity.field();
            value = entity.value();
        }
        return encode(tenant, field, value);
    }

    /** Build a bounded Kafka key for an already resolved routing tuple. */
    public static String forTuple(String tenant, String field, String value) {
        return encode(tenant, field, value);
    }

    public static String forSearchEvent(String source, String host,
                                        Map<String, String> fields) {
        String tenant = first(fields, TENANT_FIELD, "tenantId", "tenant");
        Entity entity = resolve(tenant, source, host, fields);
        return encode(entity.tenant(), entity.field(), entity.value());
    }

    /** Returns the field used by the default routing policy. */
    public static String field(String source, String host, Map<String, String> fields) {
        return resolve(first(fields, TENANT_FIELD, "tenantId", "tenant"), source, host, fields).field();
    }

    /** Returns the value used by the default routing policy. */
    public static String value(String source, String host, Map<String, String> fields) {
        return resolve(first(fields, TENANT_FIELD, "tenantId", "tenant"), source, host, fields).value();
    }

    /** Whether a stateful rule's grouping field is owned by this event key. */
    public static boolean isPartitionLocal(SecurityEvent event, String ruleKeyField) {
        if (ruleKeyField == null || ruleKeyField.isBlank()) return false;
        String routingField = first(event == null ? null : event.fields(), ROUTING_FIELD, "routing_field");
        if (routingField == null && event != null) {
            routingField = resolve(first(event.fields(), TENANT_FIELD, "tenantId", "tenant"),
                    event.source(), event.host(), event.fields()).field();
        }
        return ruleKeyField.equals(routingField);
    }

    /**
     * The dimension the default policy selects first for a source when every
     * candidate field is present. Content validation uses this to state which
     * grouping dimensions can lose to a higher-priority dimension.
     */
    public static String primaryDimension(String source) {
        return ENDPOINT_SOURCES.contains(normalize(source)) ? "host" : "src_ip";
    }

    private static String normalize(String value) {
        return blank(value, "unknown").toLowerCase(Locale.ROOT);
    }

    private static Entity resolve(String tenant, String source, String host, Map<String, String> fields) {
        String normalizedTenant = blank(tenant, "default");
        String explicitField = first(fields, ROUTING_FIELD, "routing_field");
        if (explicitField != null) {
            String explicitValue = first(fields, ROUTING_VALUE, "routing_value", explicitField);
            if (explicitValue != null) return new Entity(normalizedTenant, explicitField, explicitValue);
        }

        String preferred = primaryDimension(source);
        String preferredValue = valueOf(fields, preferred, "host".equals(preferred) ? host : null);
        if (preferredValue != null) {
            return new Entity(normalizedTenant, preferred, preferredValue);
        }
        for (String candidate : new String[]{"src_ip", "user", "host", "dst_ip"}) {
            String value = valueOf(fields, candidate, "host".equals(candidate) ? host : null);
            if (value != null) return new Entity(normalizedTenant, candidate, value);
        }
        return new Entity(normalizedTenant, "host", blank(host, "unknown"));
    }

    private static String valueOf(Map<String, String> fields, String field, String fallback) {
        String value = first(fields, field);
        return value == null ? blankOrNull(fallback) : value;
    }

    private static String encode(String tenant, String field, String value) {
        // Keep keys bounded even when an untrusted log field is unusually long.
        String raw = component(tenant, "default") + "|"
                + component(field, "unknown") + "|" + component(value, "unknown");
        return raw.length() <= 240 ? raw : raw.substring(0, 200) + "|" + digest(raw);
    }

    private static String component(String value, String fallback) {
        return blank(value, fallback).replace("%", "%25").replace("|", "%7C");
    }

    private static String first(Map<String, String> fields, String... names) {
        if (fields == null) return null;
        for (String name : names) {
            String value = fields.get(name);
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value)) return value.trim();
        }
        return null;
    }

    private static String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String blankOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private record Entity(String tenant, String field, String value) {
    }
}
