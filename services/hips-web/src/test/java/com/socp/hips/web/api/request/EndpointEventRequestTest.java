package com.socp.hips.web.api.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EndpointEventRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void nativeFalcoContextAndLegacyAgentAliasesSurviveTheBoundary() throws Exception {
        var request = mapper.readValue("""
                {"rule":"shell","hostname":"host","priority":"Warning","output":"shell spawned",
                 "time":"2026-09-22T12:30:45.123456789Z","source":"syscall","tags":["execution","container"],
                 "output_fields":{"proc.name":"bash","proc.pid":42,"evt.time":1790080245123456789,"user.name":"alice","user.uid":1001},
                 "fields":{"container.id":"container-1"}}
                """, EndpointEventRequest.class);
        assertValid(request);
        assertThat(request.asMap()).containsEntry("time", "2026-09-22T12:30:45.123456789Z")
                .containsEntry("timestamp", "2026-09-22T12:30:45.123456789Z")
                .containsEntry("source", "syscall").containsEntry("tags", List.of("execution", "container"))
                .containsEntry("output_fields", request.outputFields()).containsEntry("fields", request.fields());
        var agent = mapper.readValue("{\"type\":\"process\",\"proc\":\"bash\",\"ts\":\"2026-09-22T12:00:00Z\"}", EndpointEventRequest.class);
        assertThat(agent.asMap()).containsEntry("proc", "bash").containsEntry("process", "bash")
                .containsEntry("ts", "2026-09-22T12:00:00Z").containsEntry("timestamp", "2026-09-22T12:00:00Z");
    }

    @Test void validationRejectsBlankEventsAndUnboundedStructuredContent() throws Exception {
        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 129; i++) tooMany.put("k" + i, "value");
        Map<String, Object> oversizedTotal = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) oversizedTotal.put("k" + i, "v".repeat(4096));
        var invalid = List.of(Map.of("hostname", " "), Map.of(),
                Map.of("hostname", "host", "output_fields", tooMany),
                Map.of("hostname", "host", "output_fields", oversizedTotal),
                Map.of("hostname", "host", "output_fields", Map.of("proc.name", List.of("bash"))),
                Map.of("hostname", "host", "output_fields", Map.of("nested", Map.of("k", "v"))),
                Map.of("hostname", "host", "output_fields", Map.of(" ", "value")),
                Map.of("hostname", "host", "output_fields", Map.of("k".repeat(129), "value")),
                Map.of("hostname", "host", "output_fields", Map.of("proc.cmdline", "x".repeat(4097))),
                Map.of("hostname", "host", "tags", List.of(" ")),
                Map.of("hostname", "host", "tags", java.util.Collections.nCopies(65, "tag")));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            for (var input : invalid) {
                var request = mapper.readValue(mapper.writeValueAsString(input), EndpointEventRequest.class);
                assertThat(factory.getValidator().validate(request)).as("rejected bounded envelope").isNotEmpty();
            }
        }
    }

    private void assertValid(EndpointEventRequest request) {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }
}
