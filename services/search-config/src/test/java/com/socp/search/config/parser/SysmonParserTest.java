package com.socp.search.config.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SysmonParserTest {
    private final SysmonParser parser = new SysmonParser();

    @Test
    void mapsProcessCreateToCanonicalFields() {
        String raw = """
                {"Event":{"EventID":1,"Computer":"WIN-1","EventData":{
                  "Image":"C:\\\\Windows\\\\System32\\\\WindowsPowerShell\\\\v1.0\\\\powershell.exe",
                  "ProcessId":"42","CommandLine":"powershell -EncodedCommand abc","User":"ACME\\\\alice"}}}
                """;

        var event = parser.parse(raw);

        assertThat(event).containsEntry(CanonicalEvent.EVENT_CODE, "1")
                .containsEntry(CanonicalEvent.PROCESS_NAME,
                        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe")
                .containsEntry(CanonicalEvent.PROCESS_COMMAND_LINE, "powershell -EncodedCommand abc")
                .containsEntry(CanonicalEvent.USER_NAME, "ACME\\alice")
                .containsEntry(CanonicalEvent.EVENT_TYPE, "process_start");
    }

    @Test
    void ignoresNonSysmonJsonAndMapsNetworkEventType() {
        assertThat(parser.parse("{\"message\":\"not sysmon\"}")).isNull();
        var event = parser.parse("{\"Event\":{\"EventID\":3,\"EventData\":{\"DestinationIp\":\"203.0.113.10\"}}}");
        assertThat(event).containsEntry(CanonicalEvent.DESTINATION_IP, "203.0.113.10")
                .containsEntry(CanonicalEvent.EVENT_TYPE, "network_connection");
    }
}
