package com.socp.search.config.service;

import com.socp.search.config.domain.ParseRule;
import com.socp.search.config.parser.ParserRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseRuleExecutorTest {

    private final ParseRuleExecutor executor = new ParseRuleExecutor(new ParserRegistry());

    @Test
    void regexExtractsCanonicalFieldsAndAppliesFilters() {
        ParseRule rule = ParseRule.create(
                "auth", null, "REGEX",
                "user=(?<username>\\S+) src=(?<srcip>\\S+) port=(?<port>\\d+)",
                List.of(
                        new ParseRule.FieldMapping("username", "user", null),
                        new ParseRule.FieldMapping("srcip", "src_ip", null),
                        new ParseRule.FieldMapping("port", "src_port", null)),
                List.of(new ParseRule.FieldMapping("category", "auth_result", "failed")),
                List.of(
                        Map.of("type", "lowercase", "field", "user"),
                        Map.of("type", "convert", "field", "src_port", "to", "integer"),
                        Map.of("type", "copy", "from", "source.ip", "to", "client.ip")),
                true, 1);

        ParseRuleExecutor.Result result = executor.execute(rule,
                "user=ADMIN src=203.0.113.10 port=22");

        assertTrue(result.matched());
        assertEquals("admin", result.fields().get("user.name"));
        assertEquals("203.0.113.10", result.fields().get("source.ip"));
        assertEquals("22", result.fields().get("source.port"));
        assertEquals("203.0.113.10", result.fields().get("client.ip"));
        assertEquals("failed", result.fields().get("auth_result"));
    }

    @Test
    void jsonRulesAlsoApplySetFieldsAndFilters() {
        ParseRule rule = ParseRule.create(
                "json", null, "JSON", null, List.of(),
                List.of(new ParseRule.FieldMapping("action", "action", "login")),
                List.of(Map.of("type", "uppercase", "field", "user")), true, 1);

        ParseRuleExecutor.Result result = executor.execute(rule,
                "{\"user\":\"admin\",\"meta\":{\"ip\":\"1.2.3.4\"}}");

        assertTrue(result.matched());
        assertEquals("ADMIN", result.fields().get("user.name"));
        assertEquals("1.2.3.4", result.fields().get("meta.ip"));
        assertEquals("login", result.fields().get("event.action"));
    }

    @Test
    void kvRulesAcceptDottedAndDashedKeys() {
        ParseRule rule = ParseRule.create("kv", null, "KV", null,
                List.of(), List.of(), true, 1);

        ParseRuleExecutor.Result result = executor.execute(rule,
                "event.action=login http-status=500");

        assertTrue(result.matched());
        assertEquals("login", result.fields().get("event.action"));
        assertEquals("500", result.fields().get("http-status"));
    }

    @Test
    void fixedFormatParserUnwrapsVectorEnvelope() {
        ParseRule rule = ParseRule.create("syslog", null, "SYSLOG", null,
                List.of(), List.of(), true, 1);

        ParseRuleExecutor.Result result = executor.execute(rule,
                "{\"source_id\":\"source-1\",\"message\":\"<34>Oct 11 22:14:15 host sshd[123]: Failed password\"}");

        assertTrue(result.matched());
        assertEquals("host", result.fields().get("host.name"));
        assertEquals("Failed password", result.fields().get("event.message"));
    }

    @Test
    void rejectsUnsupportedFormatsAndConversionsAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> executor.compile(
                ParseRule.create("xml", null, "XML", null, List.of(), List.of(), true, 1)));
        assertThrows(IllegalArgumentException.class, () -> executor.compile(ParseRule.create(
                "bad-convert", null, "KV", null, List.of(), List.of(),
                List.of(Map.of("type", "convert", "field", "status", "to", "date")), true, 1)));
    }
}
