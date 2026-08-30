package com.socp.search.config.schema;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalEventSchemaTest {
    @Test
    void acceptsLegacyMissingVersionDuringRollingUpgrade() {
        assertThat(CanonicalEventSchema.effectiveVersion(null)).isEqualTo("1.0");
        CanonicalEventSchema.requireSupported(Map.of("eventId", "e1"));
    }

    @Test
    void rejectsUnknownVersionsExplicitly() {
        assertThatThrownBy(() -> CanonicalEventSchema.requireSupported(Map.of("schemaVersion", "2.0")))
                .isInstanceOf(CanonicalEventSchema.UnsupportedSchemaVersionException.class)
                .hasMessageContaining("2.0");
    }

    @Test
    void validatesOptedInEnvelopeBeforeItCrossesKafkaBoundary() {
        assertThatThrownBy(() -> CanonicalEventSchema.requireSupported(Map.of(
                "schemaVersion", "1.0", "eventId", "e-1", "tenantId", "tenant-a")))
                .isInstanceOf(CanonicalEventSchema.SchemaValidationException.class)
                .hasMessageContaining("timestamp");

        CanonicalEventSchema.requireSupported(Map.of(
                "schemaVersion", "1.0", "eventId", "e-1", "tenantId", "tenant-a",
                "timestamp", "2026-08-30T00:00:00Z", "source", "sysmon", "host", "win-1",
                "severity", "HIGH", "msg", "powershell", "fields", Map.of()));
    }
}
