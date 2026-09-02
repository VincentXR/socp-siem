package com.socp.search.config.parser;

import com.socp.search.config.domain.ParseFormat;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserCoverageTest {

    @Test
    void jsonParserFlattensNestedObjectsAndMapsCanonicalAliases() {
        Map<String, String> parsed = new JsonParser().parse("""
                {"host":"web-1","src_ip":"198.51.100.10","nested":{"pid":7},
                 "msg":"login failed","blank":"","nullValue":null}
                """);

        assertEquals("web-1", parsed.get(CanonicalEvent.HOST_NAME));
        assertEquals("198.51.100.10", parsed.get(CanonicalEvent.SOURCE_IP));
        assertEquals("7", parsed.get("nested.pid"));
        assertEquals("login failed", parsed.get(CanonicalEvent.EVENT_MESSAGE));
        assertFalse(parsed.containsKey("blank"));
        assertNull(new JsonParser().parse("plain text"));
        assertThrows(IllegalArgumentException.class, () -> new JsonParser().parse("{broken"));
    }

    @Test
    void syslogParserSupportsRfc5424AndRfc3164AndRejectsOtherShapes() {
        Map<String, String> rfc5424 = new SyslogParser().parse(
                "<134>1 2003-10-11T22:14:15.003Z mymachine sshd - - login accepted");
        assertEquals("syslog", rfc5424.get("vendor"));
        assertEquals("INFO", rfc5424.get(CanonicalEvent.EVENT_SEVERITY));
        assertEquals("login accepted", rfc5424.get(CanonicalEvent.EVENT_MESSAGE));

        Map<String, String> rfc3164 = new SyslogParser().parse(
                "<34>Oct 11 22:14:15 mymachine su[1123]: failed for root");
        assertEquals("mymachine", rfc3164.get(CanonicalEvent.HOST_NAME));
        assertEquals("1123", rfc3164.get(CanonicalEvent.PROCESS_PID));
        assertEquals("CRITICAL", rfc3164.get(CanonicalEvent.EVENT_SEVERITY));
        assertNull(new SyslogParser().parse("not syslog"));
        assertNull(new SyslogParser().parse("<tag> message"));
    }

    @Test
    void cefAndLeefParsersMapExtensionsAndSeverity() {
        Map<String, String> cef = new CefParser().parse(
                "CEF:0|Fortinet|FortiGate|v7.2|52002|traffic|5|"
                        + "src=1.2.3.4 dst=5.6.7.8 spt=123 dpt=80 proto=tcp "
                        + "cs1=alice dvc=fw act=blocked msg=denied custom=value");
        assertEquals("5.6.7.8", cef.get(CanonicalEvent.DESTINATION_IP));
        assertEquals("123", cef.get(CanonicalEvent.SOURCE_PORT));
        assertEquals("tcp", cef.get(CanonicalEvent.NETWORK_PROTOCOL));
        assertEquals("blocked", cef.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("fw", cef.get(CanonicalEvent.HOST_NAME));
        assertEquals("denied", cef.get(CanonicalEvent.EVENT_MESSAGE));
        assertEquals("value", cef.get("cef.custom"));

        Map<String, String> leef = new LeefParser().parse(
                "LEEF:1.0|Microsoft|Sysmon|10.0|1|Process creation|5|"
                        + "src=1.2.3.4\tdst=5.6.7.8\tproto=tcp\tsev=5");
        assertEquals("1.2.3.4", leef.get(CanonicalEvent.SOURCE_IP));
        assertEquals("MEDIUM", leef.get(CanonicalEvent.EVENT_SEVERITY));
        assertEquals("tcp", leef.get(CanonicalEvent.NETWORK_PROTOCOL));

        assertThrows(IllegalArgumentException.class,
                () -> new CefParser().parse("CEF:0|too-short"));
        assertThrows(IllegalArgumentException.class,
                () -> new LeefParser().parse("LEEF:1.0|too-short"));
        assertEquals("custom", new CefParser().parse(
                "CEF:0|v|p|1|id|name|custom").get(CanonicalEvent.EVENT_SEVERITY));
    }

    @Test
    void kvParserHandlesQuotedValuesAndCanonicalAliases() {
        Map<String, String> parsed = new KvParser().parse(
                "src_ip=198.51.100.1 msg=\"hello world\" proto=tcp empty=\"\"");
        assertEquals("198.51.100.1", parsed.get(CanonicalEvent.SOURCE_IP));
        assertEquals("hello world", parsed.get(CanonicalEvent.EVENT_MESSAGE));
        assertEquals("tcp", parsed.get(CanonicalEvent.NETWORK_PROTOCOL));
        assertFalse(parsed.containsKey("empty"));
        assertEquals("unfinished", new KvParser().parse("key=\"unfinished").get("key"));
        assertNull(new KvParser().parse("no key value"));
        assertNull(new KvParser().parse(" =value"));
    }

    @Test
    void falcoParserMapsContainerFieldsAndPriorityVariants() {
        Map<String, String> parsed = new FalcoParser().parse("""
                {"rule":"Terminal shell","output":"shell spawned","priority":"Warning",
                 "hostname":"node-1","time":"2026-08-30T12:00:00Z",
                 "fields":{"proc.name":"bash","proc.cmdline":"bash -i","proc.pid":42,
                 "user.name":"alice","container.id":"cid","evt.type":"Execve",
                 "fd.name":"/tmp/x","fd.ip":"10.0.0.2"}}
                """);
        assertEquals("Terminal shell", parsed.get(CanonicalEvent.EVENT_CODE));
        assertEquals("MEDIUM", parsed.get(CanonicalEvent.EVENT_SEVERITY));
        assertEquals("bash", parsed.get(CanonicalEvent.PROCESS_NAME));
        assertEquals("alice", parsed.get(CanonicalEvent.USER_NAME));
        assertEquals("execve", parsed.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("10.0.0.2", parsed.get(CanonicalEvent.DESTINATION_IP));
        assertNull(new FalcoParser().parse("{\"rule\":\"not falco\"}"));
        assertNull(new FalcoParser().parse("{broken"));
        assertEquals("CRITICAL", new FalcoParser().parse(
                "{\"rule\":\"r\",\"priority\":\"emergency\"}").get(CanonicalEvent.EVENT_SEVERITY));
    }

    @Test
    void auditdAndSysmonParsersMapProcessAndNetworkFields() {
        Map<String, String> audit = new AuditdParser().parse("""
                {"type":"EXECVE","exe":"/usr/bin/curl","pid":42,"uid":1000,"key":"net",
                 "a0":"curl","a1":"-k","a2":"https://example.test","cwd":"/tmp"}
                """);
        assertEquals("auditd", audit.get("vendor"));
        assertEquals("/usr/bin/curl", audit.get(CanonicalEvent.PROCESS_NAME));
        assertEquals("curl -k https://example.test", audit.get(CanonicalEvent.PROCESS_COMMAND_LINE));
        assertEquals("/tmp", audit.get(CanonicalEvent.FILE_PATH));
        Map<String, String> comm = new AuditdParser().parse("{\"type\":\"SYSCALL\",\"comm\":\"bash\"}");
        assertEquals("bash", comm.get(CanonicalEvent.PROCESS_NAME));
        assertNull(new AuditdParser().parse("{\"message\":\"ordinary\"}"));

        Map<String, String> sysmon = new SysmonParser().parse("""
                {"Event":{"EventID":3,"EventData":{"Image":"C:\\\\Windows\\\\cmd.exe",
                 "ProcessId":"1234","SourceIp":"10.0.0.1","DestinationIp":"10.0.0.2",
                 "DestinationPort":"443","Protocol":"tcp","User":"alice"}}}
                """);
        assertEquals("3", sysmon.get(CanonicalEvent.EVENT_CODE));
        assertEquals("network_connection", sysmon.get(CanonicalEvent.EVENT_TYPE));
        assertEquals("10.0.0.2", sysmon.get(CanonicalEvent.DESTINATION_IP));
        assertEquals("tcp", sysmon.get(CanonicalEvent.NETWORK_PROTOCOL));
        assertNull(new SysmonParser().parse("{\"Event\":{}}"));
        assertEquals("event", new SysmonParser().parse(
                "{\"Event\":{\"EventData\":{\"EventID\":\"99\"}}}")
                .get(CanonicalEvent.EVENT_TYPE));
    }

    @Test
    void parserRegistryRoutesHintsEmbeddedAuthLinesAndFallbacks() {
        ParserRegistry registry = new ParserRegistry();
        assertEquals(9, registry.parserNames().size());
        assertTrue(registry.parserNames().contains("syslog"));
        assertEquals(Map.of(), registry.parse(null, null));

        String ssh = "2026-08-30T12:00:00Z web-1 sshd[22]: Failed password for root from 203.0.113.5";
        Map<String, String> hinted = registry.parse(ssh, "SSHD");
        assertEquals("login_failed", hinted.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("root", hinted.get(CanonicalEvent.USER_NAME));

        Map<String, String> embedded = registry.parse(
                "{\"collector\":\"vector\",\"message\":\"" + ssh + "\"}", null);
        assertEquals("login_failed", embedded.get(CanonicalEvent.EVENT_ACTION));
        assertEquals("vector", embedded.get("collector"));

        Map<String, String> fallback = registry.parse("ordinary unstructured message", "unknown");
        assertEquals("ordinary unstructured message", fallback.get(CanonicalEvent.EVENT_MESSAGE));
        Map<String, String> parseError = registry.parse("{broken", "json");
        assertTrue(parseError.containsKey("parse.error"));
    }

    @Test
    void parserRegistryHandlesFixedFormatsAndJsonEnvelopes() {
        ParserRegistry registry = new ParserRegistry();

        assertEquals(Map.of(), registry.parse(" ", ParseFormat.JSON, null));
        assertEquals("source-1", registry.parse(
                "{\"source_id\":\"source-1\"}", ParseFormat.SYSLOG, null)
                .get("source_id"));

        Map<String, String> unmatchedEnvelope = registry.parse(
                "{\"message\":\"not syslog\"}", ParseFormat.SYSLOG, null);
        assertEquals("not syslog", unmatchedEnvelope.get(CanonicalEvent.EVENT_MESSAGE));

        Map<String, String> directSyslog = registry.parse(
                "<34>Oct 11 22:14:15 host sshd[123]: failed", ParseFormat.SYSLOG, null);
        assertEquals("host", directSyslog.get(CanonicalEvent.HOST_NAME));

        Map<String, String> nestedJson = registry.parse(
                "{\"message\":\"{\\\"action\\\":\\\"login\\\"}\"}",
                ParseFormat.JSON, null);
        assertEquals("login", nestedJson.get(CanonicalEvent.EVENT_ACTION));

        Map<String, String> plainJsonEnvelope = registry.parse(
                "{\"message\":\"plain text\"}", ParseFormat.JSON, null);
        assertEquals("plain text", plainJsonEnvelope.get(CanonicalEvent.EVENT_MESSAGE));

        Map<String, String> emptyNestedJson = registry.parse(
                "{\"message\":\"{}\"}", ParseFormat.JSON, null);
        assertEquals("{}", emptyNestedJson.get(CanonicalEvent.EVENT_MESSAGE));
    }
}
