package com.socp.search.config.parser;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SshdParserTest {

    private final SshdParser parser = new SshdParser();

    @Test
    void parsesFailedLoginIntoCanonicalAuthenticationFields() {
        Map<String, String> event = parser.parse(
                "2026-08-15T12:00:00Z demo-host sshd[1234]: "
                        + "Failed password for invalid user root from 203.0.113.77 port 51234 ssh2");

        assertEquals("authentication", event.get(CanonicalEvent.EVENT_CATEGORY));
        assertEquals("login_failed", event.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("root", event.get(CanonicalEvent.USER_NAME));
        assertEquals("203.0.113.77", event.get(CanonicalEvent.SOURCE_IP));
        assertEquals("51234", event.get(CanonicalEvent.SOURCE_PORT));
        assertEquals("demo-host", event.get(CanonicalEvent.HOST_NAME));
    }

    @Test
    void parsesAcceptedLoginAndIgnoresOtherFormats() {
        Map<String, String> event = parser.parse(
                "2026-08-15T12:01:00Z sshd[1234]: Accepted publickey for root from 203.0.113.77 port 51234 ssh2");

        assertEquals("login_success", event.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("root", event.get(CanonicalEvent.USER_NAME));
        assertNull(parser.parse("not an sshd line"));
    }

    @Test
    void enrichesVectorJsonEnvelopeWithoutDroppingCollectorMetadata() {
        Map<String, String> event = new ParserRegistry().parse(
                "{\"message\":\"2026-08-15T12:00:00Z demo-host sshd[1234]: "
                        + "Failed password for root from 203.0.113.77 port 51234 ssh2\","
                        + "\"collector_host\":\"vector-local\",\"parse_format\":\"auto\"}", null);

        assertEquals("login_failed", event.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("203.0.113.77", event.get(CanonicalEvent.SOURCE_IP));
        assertEquals("vector-local", event.get("collector_host"));
    }
}
