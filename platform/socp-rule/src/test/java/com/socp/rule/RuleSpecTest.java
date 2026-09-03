package com.socp.rule;

import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import com.socp.rule.util.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuleSpec 单测：JSON 配置 → Rule 实例 → 事件匹配（含 regex/数值/跨事件）。
 */
class RuleSpecTest {

    private static SecurityEvent ev(String source, String msg, String srcIp) {
        return new SecurityEvent(Instant.now(), source, "h1", msg,
                Map.of("msg", msg, "src_ip", srcIp == null ? "" : srcIp), Severity.INFO);
    }

    @Test
    void thresholdSpecFiresFromJson() {
        String json = """
                {"id":"T1","name":"暴力破解","type":"threshold","severity":"HIGH",
                 "message":"src {key} brute","keyField":"src_ip","threshold":3,"window":"60s",
                 "match":[{"field":"msg","op":"contains","value":"Failed password"}]}
                """;
        Rule r = new RuleSpec(Json.parseObject(json)).toRule();
        assertFalse(r.id().isBlank());

        r.accept(ev("auth", "Failed password for x", "1.2.3.4"));
        r.accept(ev("auth", "Failed password for x", "1.2.3.4"));
        r.accept(ev("auth", "Failed password for x", "1.2.3.4"));
        assertEquals(1, r.drain().size(), "3 次命中应触发阈值告警");
    }

    @Test
    void patternSpecSupportsRegexAndSeverityOps() {
        String json = """
                {"id":"P1","name":"SQLi","type":"pattern","severity":"CRITICAL",
                 "message":"sqli: {msg}",
                 "match":[{"field":"msg","op":"regex","value":"(?i)union\\\\s+select|or\\\\s+1=1"}]}
                """;
        Rule r = new RuleSpec(Json.parseObject(json)).toRule();
        r.accept(ev("web", "q=1' OR 1=1 --", null));
        assertEquals(1, r.drain().size(), "regex 命中");

        r.accept(ev("web", "normal request", null));
        assertEquals(0, r.drain().size(), "不匹配不告警");
    }

    @Test
    void numericOpAndGeSeverity() {
        String json = """
                {"id":"N1","name":"大流量","type":"pattern","severity":"LOW",
                 "message":"bytes {bytes}",
                 "match":[{"field":"bytes","op":"gt","value":"1000000"},
                          {"field":"severity","op":"ge","value":"HIGH"}]}
                """;
        Rule r = new RuleSpec(Json.parseObject(json)).toRule();

        SecurityEvent big = new SecurityEvent(Instant.now(), "fw", "h1", "big",
                Map.of("msg", "big", "src_ip", "x", "bytes", "5000000"), Severity.HIGH);
        r.accept(big);
        assertEquals(1, r.drain().size(), "数值+级别双条件命中");

        SecurityEvent small = new SecurityEvent(Instant.now(), "fw", "h1", "small",
                Map.of("msg", "small", "src_ip", "x", "bytes", "100"), Severity.HIGH);
        r.accept(small);
        assertEquals(0, r.drain().size(), "数值不满足不告警");
    }

    @Test
    void correlationSetSpecFromJson() {
        String json = """
                {"id":"C1","name":"横向移动","type":"correlation-set","severity":"CRITICAL",
                 "message":"{key} lateral","keyField":"src_ip","window":"120s",
                 "steps":[
                    [{"field":"msg","op":"contains","value":"blocked"}],
                    [{"field":"msg","op":"contains","value":"Accepted password"}]
                 ]}
                """;
        Rule r = new RuleSpec(Json.parseObject(json)).toRule();
        r.accept(ev("fw", "blocked", "5.6.7.8"));
        assertEquals(0, r.drain().size(), "仅一个条件不告警");
        r.accept(ev("auth", "Accepted password", "5.6.7.8"));
        assertEquals(1, r.drain().size(), "两条件齐备应告警");
    }

    @Test
    void toMapRoundTrips() {
        String json = """
                {"id":"R1","name":"n","type":"pattern","severity":"HIGH","message":"m",
                 "match":[{"field":"source","op":"eq","value":"web"}]}
                """;
        RuleSpec spec = new RuleSpec(Json.parseObject(json));
        Map<String, Object> map = spec.toMap();
        assertEquals("R1", map.get("id"));
        assertEquals("pattern", map.get("type"));
        assertTrue(map.containsKey("match"));
        assertTrue(List.of("id", "name", "type", "severity", "message", "window", "enabled")
                .stream().allMatch(map::containsKey), "关键字段齐全");
    }

    @Test
    void statefulRoutingFieldDefaultsToGroupingFieldAndRoundTrips() {
        RuleSpec spec = new RuleSpec(Json.parseObject("""
                {"id":"R2","name":"n","type":"threshold","severity":"HIGH",
                 "keyField":"host","threshold":2}
                """));
        assertEquals("host", spec.routingField);
        assertEquals("host", spec.toMap().get("routingField"));
    }

    @Test
    void alertTemplatesRenderAndWhitelistSkipsMatchingEvents() {
        String watchlist = "rule-spec-test-" + System.nanoTime();
        Watchlists.put(watchlist, List.of("10.0.0.1"));
        try {
            RuleSpec spec = new RuleSpec(Json.parseObject("""
                    {"id":"P-WL","name":"Suspicious login","type":"pattern","severity":"HIGH",
                     "alert":{"title":"Login from {{event.src_ip}}","description":"Host {{event.host}} matched {{event.msg}}"},
                     "match":[{"field":"msg","op":"contains","value":"login"}],
                     "whitelist":[{"field":"src_ip","op":"inlist","value":"%s"}]}
                    """.formatted(watchlist)));
            Rule rule = spec.toRule();

            rule.accept(ev("auth", "login accepted", "10.0.0.1"));
            assertTrue(rule.drain().isEmpty(), "watchlisted source must be excluded");

            rule.accept(ev("auth", "login accepted", "10.0.0.2"));
            var alert = rule.drain().getFirst();
            assertEquals("Login from 10.0.0.2", alert.title());
            assertEquals("Host h1 matched login accepted", alert.message());
            assertEquals(watchlist, spec.whitelist.getFirst().get("value"));
        } finally {
            Watchlists.delete(watchlist);
        }
    }
}
