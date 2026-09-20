package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoutingDimensionTest {

    @Test
    void resolvesAliasesAndLengthPrefixesCompositeKeys() {
        SecurityEvent event = new SecurityEvent("e-1", Instant.EPOCH, "auth", "top-host", "raw",
                Map.of("tenant_id", "tenant-a",
                        "username", "alice",
                        "source.ip", "198.51.100.9",
                        "host.name", "field-host"), Severity.INFO);

        assertEquals("alice", RoutingDimension.value(event, "user"));
        assertEquals("198.51.100.9", RoutingDimension.value(event, "src_ip"));
        assertEquals("field-host", RoutingDimension.value(event, "host"));
        assertEquals("5:alice|12:198.51.100.9",
                RoutingDimension.value(event, "user+src_ip"));
    }

    @Test
    void compositeKeyRequiresEveryComponentAndHasBoundedGrammar() {
        SecurityEvent event = new SecurityEvent("e-2", Instant.EPOCH, "auth", "host", "raw",
                Map.of("tenant_id", "tenant-a", "user", "alice"), Severity.INFO);

        assertNull(RoutingDimension.value(event, "user+src_ip"));
        assertFalse(RoutingDimension.validationErrors("user+src_ip").iterator().hasNext());
        assertFalse(RoutingDimension.validationErrors("user+src_ip+host+dst_ip").iterator().hasNext());
        org.junit.jupiter.api.Assertions.assertFalse(
                RoutingDimension.validationErrors("user+src_ip+host+dst_ip+source").isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(
                RoutingDimension.validationErrors("user+bad field").isEmpty());
    }
}
