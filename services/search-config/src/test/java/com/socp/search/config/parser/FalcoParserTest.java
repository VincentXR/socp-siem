package com.socp.search.config.parser;

import org.junit.jupiter.api.Test;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class FalcoParserTest {
    @Test void arbitraryVendorKeysDoNotIncreaseTheCanonicalFieldSet() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Set<String> previousKeys = null;
        for (int batch = 0; batch < 3; batch++) {
            var fields = new java.util.LinkedHashMap<String, Object>();
            for (int i = 0; i < 100; i++) fields.put("custom-" + batch + "-" + i, i);
            String raw = mapper.writeValueAsString(java.util.Map.of("rule", "r", "priority", "Warning", "output_fields", fields));
            var parsed = new FalcoParser().parse(raw);
            assertThat(mapper.readTree(parsed.get("falco.output_fields"))).isEqualTo(mapper.valueToTree(fields));
            if (previousKeys != null) assertThat(parsed.keySet()).isEqualTo(previousKeys);
            previousKeys = parsed.keySet();
        }
    }
    @Test void nativeOutputFieldsPreserveUserIdentityAndVendorEvidence() throws Exception {
        var parsed = new ParserRegistry().parse("""
                {"rule":"shell","output":"shell spawned","priority":"Warning","hostname":"host",
                 "time":"2026-09-22T12:30:45.123456789Z","source":"syscall","tags":["execution","container"],
                 "output_fields":{"proc.name":"bash","proc.cmdline":"bash -i","proc.pid":42,
                 "user.name":"alice","user.uid":1001,"evt.time":1790080245123456789,
                 "container.id":"container-1","k8s.pod.name":"pod-1",
                 "tenant_id":"forged","detection_routing_field":"user","ingested_at":"forged"}}
                """, (String) null);
        assertThat(parsed).containsEntry("process.name", "bash").containsEntry("process.command_line", "bash -i")
                .containsEntry("process.pid", "42").containsEntry("user.name", "alice").containsEntry("user.id", "1001")
                .containsEntry("timestamp", "2026-09-22T12:30:45.123456789Z")
                .containsEntry("vendor", "falco").containsEntry("falco.source", "syscall")
                .containsEntry("falco.tags", "[\"execution\",\"container\"]")
                .doesNotContainKeys("tenant_id", "detection_routing_field", "ingested_at");
        var original = new com.fasterxml.jackson.databind.ObjectMapper().readTree(parsed.get("falco.output_fields"));
        assertThat(original.path("k8s.pod.name").asText()).isEqualTo("pod-1");
        assertThat(original.path("evt.time").longValue()).isEqualTo(1790080245123456789L);
        assertThat(parsed.keySet()).noneMatch(key -> key.startsWith("falco.output_fields."));
    }

    @Test void nativeFieldsWinOverLegacyAndFlatAgentFieldsRemainFallbacks() {
        var parsed = new FalcoParser().parse("""
                {"rule":"r","priority":"Warning","proc":"flat","cmdline":"flat command","ts":"2026-09-22T12:00:00Z",
                 "fields":{"proc.name":"legacy","user.name":"alice"},"output_fields":{"proc.name":"native","user.uid":42}}
                """);
        assertThat(parsed).containsEntry("process.name", "native").containsEntry("process.command_line", "flat command")
                .containsEntry("user.name", "alice").containsEntry("user.id", "42")
                .containsEntry("timestamp", "2026-09-22T12:00:00Z");
    }

    @Test void priorityAndActionNormalizationDoNotDependOnServerLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var parsed = new FalcoParser().parse("{\"rule\":\"r\",\"priority\":\"WARNING\",\"output_fields\":{\"evt.type\":\"INIT\"}}");
            assertThat(parsed).containsEntry("event.severity", "MEDIUM").containsEntry("event.action", "init");
        } finally { Locale.setDefault(original); }
    }
}
