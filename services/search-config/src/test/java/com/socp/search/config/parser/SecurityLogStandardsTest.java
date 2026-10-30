package com.socp.search.config.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/** Fixtures follow ArcSight CEF, IBM LEEF event components, and RFC5424 section 6.5. */
class SecurityLogStandardsTest {
    @ParameterizedTest
    @CsvSource({"0,LOW", "1,LOW", "3,LOW", "4,MEDIUM", "6,MEDIUM", "7,HIGH", "8,HIGH", "9,CRITICAL", "10,CRITICAL",
            "Low,LOW", "Medium,MEDIUM", "High,HIGH", "Very-High,CRITICAL"})
    void numericSeverityIncreasesWithCriticality(String value, String expected) {
        assertThat(new CefParser().parse("CEF:0|ArcSight|Logger|1|100|event|" + value + "|")
                .get(CanonicalEvent.EVENT_SEVERITY)).isEqualToIgnoringCase(expected);
        assertThat(new LeefParser().parse("LEEF:1.0|IBM|QRadar|1|100|sev=" + value)
                .get(CanonicalEvent.EVENT_SEVERITY)).isEqualToIgnoringCase(expected);
    }

    @Test
    void cefLongWhitespaceDoesNotCauseRepeatedBacktracking() {
        org.junit.jupiter.api.Assertions.assertTimeout(java.time.Duration.ofSeconds(5), () -> {
            var parsed = new CefParser().parse("CEF:0|v|p|1|100|event|10|msg=hello" + " ".repeat(100_000) + "dpt=80");
            assertThat(parsed).containsEntry(CanonicalEvent.EVENT_MESSAGE, "hello")
                    .containsEntry(CanonicalEvent.DESTINATION_PORT, "80");
        });
    }

    @Test
    void cefPreservesEscapedHeaderAndWholeExtensionValues() {
        var parsed = new CefParser().parse("CEF:0|Acme\\\\Labs|Firewall|1|100|found \\| pipe|10|"
                + "msg=connection denied by policy dpt=80 cs1=literal key\\=value\\nnext line cs2=C:\\\\logs\\\\event");
        assertThat(parsed).containsEntry(CanonicalEvent.EVENT_ACTION, "found | pipe")
                .containsEntry("cef.device.vendor", "Acme\\Labs")
                .containsEntry(CanonicalEvent.EVENT_MESSAGE, "connection denied by policy")
                .containsEntry(CanonicalEvent.DESTINATION_PORT, "80")
                .containsEntry(CanonicalEvent.USER_NAME, "literal key=value\nnext line")
                .containsEntry("cef.cs2", "C:\\logs\\event")
                .containsEntry(CanonicalEvent.EVENT_SEVERITY, "CRITICAL");
    }

    @ParameterizedTest
    @CsvSource({"^,^", "0x5e,^", "x5E,^", "0x09,TAB"})
    void leefTwoUsesDeclaredLiteralOrHexDelimiter(String declaration, String separator) {
        if (separator.equals("TAB")) separator = "\t";
        var parsed = new LeefParser().parse("LEEF:2.0|IBM|IPS|1|100|" + declaration + "|"
                + "src=192.0.2.1" + separator + "sev=10" + separator + "msg=connection denied by policy"
                + separator + "dstPort=80" + separator + "usrName=alice" + separator + "custom=a=b|c");
        assertThat(parsed).containsEntry(CanonicalEvent.SOURCE_IP, "192.0.2.1")
                .containsEntry(CanonicalEvent.EVENT_MESSAGE, "connection denied by policy")
                .containsEntry(CanonicalEvent.EVENT_SEVERITY, "CRITICAL")
                .containsEntry(CanonicalEvent.DESTINATION_PORT, "80")
                .containsEntry(CanonicalEvent.USER_NAME, "alice").containsEntry("leef.custom", "a=b|c");
    }

    @Test
    void rfc5424StructuredDataAndHeaderAreSeparatedFromTheMessage() {
        var parsed = new SyslogParser().parse("<165>1 2003-10-11T22:14:15.003Z mymachine evntslog 8710 ID47 "
                + "[exampleSDID@32473 iut=\"3\" eventSource=\"Application\" eventID=\"1011\"]"
                + "[examplePriority@32473 class=\"high\\] \\\"quoted\\\" \\\\ path\"] An application event log entry...");
        assertThat(parsed).containsEntry(CanonicalEvent.EVENT_SEVERITY, "NOTICE")
                .containsEntry(CanonicalEvent.PROCESS_PID, "8710").containsEntry("syslog.msgid", "ID47")
                .containsEntry("syslog.sd.exampleSDID@32473.eventSource", "Application")
                .containsEntry("syslog.sd.examplePriority@32473.class", "high] \"quoted\" \\ path")
                .containsEntry(CanonicalEvent.EVENT_MESSAGE, "An application event log entry...");
        var nil = new SyslogParser().parse("<165>1 - - app worker-A - -");
        assertThat(nil).containsEntry("syslog.procid", "worker-A").containsEntry(CanonicalEvent.EVENT_MESSAGE, "")
                .doesNotContainKeys("timestamp", CanonicalEvent.PROCESS_PID, "syslog.msgid", CanonicalEvent.HOST_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[id key=\"unterminated]", "[id key=\"bare]bracket\"]", "[id]trailing", "body without SD"})
    void malformedStructuredDataCannotBecomeAnOrdinaryMessage(String data) {
        assertThatIllegalArgumentException().isThrownBy(() -> new SyslogParser().parse(
                "<165>1 2003-10-11T22:14:15Z host app - ID47 " + data));
    }
}
