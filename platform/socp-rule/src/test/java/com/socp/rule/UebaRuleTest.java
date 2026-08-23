package com.socp.rule;

import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import com.socp.rule.score.RiskScorer;
import com.socp.rule.util.Json;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UEBA 高级检测单测：基线离群（baseline）、首次出现（rare）、观察名单（inlist）、威胁评分。
 * 规则内部状态是纯内存的，直接喂事件断言 drain() 结果，无需启动引擎。
 */
class UebaRuleTest {

    private static SecurityEvent ev(Instant ts, String source, String host, Map<String, String> fields) {
        return new SecurityEvent(ts, source, host, "raw", fields, Severity.INFO);
    }

    @Test
    void baselineRule_reportsOnlyWhenVolumeDeviatesFromEntityHistory() {
        Rule r = new RuleSpec(Json.parseObject("""
                {"id":"B1","name":"基线突增","type":"baseline","severity":"HIGH",
                 "message":"{key} 当前 {count} 次，基线 {baseline}（z={z}）",
                 "keyField":"src_ip","window":"60s","baselineWindows":12,"warmup":3,
                 "sigma":3.0,"minCount":5,
                 "match":[{"field":"source","op":"eq","value":"auth"}]}
                """)).toRule();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // 前 5 个窗口各 2 次，建立"该实体平时很安静"的基线
        for (int w = 0; w < 5; w++) {
            for (int i = 0; i < 2; i++) {
                r.accept(ev(t0.plusSeconds(w * 60L + i), "auth", "h1", Map.of("src_ip", "10.1.1.1")));
            }
        }
        assertTrue(r.drain().isEmpty(), "学习期与正常水位不应告警");

        // 第 6 个窗口暴增到 30 次
        for (int i = 0; i < 30; i++) {
            r.accept(ev(t0.plusSeconds(5 * 60L + i), "auth", "h1", Map.of("src_ip", "10.1.1.1")));
        }
        List<Alert> alerts = r.drain();
        assertEquals(1, alerts.size(), "突增应且仅应告警一次（同窗口不刷屏）");
        assertEquals("10.1.1.1", alerts.get(0).entity());
        // 基线 μ=2、σ 取下限 1、k=3 ⇒ 触发线 5，第 6 条就越线告警，
        // 不必等窗口跑完 30 条——这正是流式基线检测的价值：越早发现越好
        assertTrue(alerts.get(0).message().contains("当前 6 次"),
                "应在越过触发线的瞬间告警，实际消息=" + alerts.get(0).message());
        assertTrue(alerts.get(0).message().contains("基线 2.0"), "消息应说明基线水位，便于分析师判断");
    }

    @Test
    void baselineRule_doesNotFireForEntityWithNaturallyHighVolume() {
        Rule r = new RuleSpec(Json.parseObject("""
                {"id":"B2","name":"基线突增","type":"baseline","severity":"HIGH",
                 "message":"{key} 异常","keyField":"src_ip","window":"60s",
                 "baselineWindows":12,"warmup":3,"sigma":3.0,"minCount":5,"match":[]}
                """)).toRule();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // 这个实体天生就很忙：每个窗口 28~32 次
        int[] volumes = {30, 28, 31, 29, 32, 30};
        for (int w = 0; w < volumes.length; w++) {
            for (int i = 0; i < volumes[w]; i++) {
                r.accept(ev(t0.plusSeconds(w * 60L + i), "auth", "h1", Map.of("src_ip", "10.2.2.2")));
            }
        }
        assertTrue(r.drain().isEmpty(), "高但稳定的水位不是异常——这正是基线优于固定阈值之处");
    }

    @Test
    void rareValueRule_firesOnFirstSeenValueAfterWarmup() {
        Rule r = new RuleSpec(Json.parseObject("""
                {"id":"R1","name":"首次异地登录","type":"rare","severity":"HIGH",
                 "message":"{key} 首次从 {value} 登录（已知 {known} 个）",
                 "keyField":"user","valueField":"geo","warmup":3,
                 "match":[{"field":"source","op":"eq","value":"auth"}]}
                """)).toRule();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        // 学习期：4 次观察（warmup=3，第 4 次起才告警），都在 beijing
        for (int i = 0; i < 4; i++) {
            r.accept(ev(t0.plusSeconds(i), "auth", "h1", Map.of("user", "alice", "geo", "beijing")));
        }
        assertTrue(r.drain().isEmpty(), "学习期内的首次取值只应被记忆，不应告警");

        // 学习期后出现新地域
        r.accept(ev(t0.plusSeconds(100), "auth", "h1", Map.of("user", "alice", "geo", "unknown-xx")));
        List<Alert> alerts = r.drain();
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).message().contains("unknown-xx"));

        // 同一个新地域再来一次不应重复告警（已进入画像）
        r.accept(ev(t0.plusSeconds(101), "auth", "h1", Map.of("user", "alice", "geo", "unknown-xx")));
        assertTrue(r.drain().isEmpty(), "已学习过的取值不应再告警");
    }

    @Test
    void watchlistOperator_isEvaluatedAgainstLiveRegistry() {
        Watchlists.put("test_bad_ips", List.of("203.0.113.66"));
        Rule r = new RuleSpec(Json.parseObject("""
                {"id":"W1","name":"封禁名单活动","type":"pattern","severity":"CRITICAL",
                 "message":"名单 IP 活动","match":[{"field":"src_ip","op":"inlist","value":"test_bad_ips"}]}
                """)).toRule();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        r.accept(ev(t0, "firewall", "h1", Map.of("src_ip", "10.0.0.5")));
        assertTrue(r.drain().isEmpty(), "不在名单内不应命中");

        r.accept(ev(t0, "firewall", "h1", Map.of("src_ip", "203.0.113.66")));
        assertEquals(1, r.drain().size(), "名单内 IP 应命中");

        // 名单动态变更后规则行为立即改变，无需重载规则
        Watchlists.delete("test_bad_ips");
        r.accept(ev(t0, "firewall", "h1", Map.of("src_ip", "203.0.113.66")));
        assertTrue(r.drain().isEmpty(), "名单删除后同一事件不应再命中");
    }

    @Test
    void watchlistOperator_isIsolatedByEventTenant() {
        Watchlists.put("tenant-a", "shared_name", List.of("203.0.113.66"));
        Watchlists.put("tenant-b", "shared_name", List.of("198.51.100.23"));
        Rule rule = new RuleSpec(Json.parseObject("""
                {"id":"W2","name":"tenant watchlist","type":"pattern","severity":"HIGH",
                 "message":"watchlist hit","match":[{"field":"src_ip","op":"inlist","value":"shared_name"}]}
                """)).toRule();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        rule.accept(new SecurityEvent("same-id", now, "firewall", "h1", "raw",
                Map.of("tenant_id", "tenant-a", "src_ip", "203.0.113.66"), Severity.INFO));
        assertEquals(1, rule.drain().size());

        rule.accept(new SecurityEvent("same-id", now, "firewall", "h1", "raw",
                Map.of("tenant_id", "tenant-b", "src_ip", "203.0.113.66"), Severity.INFO));
        assertTrue(rule.drain().isEmpty());

        Watchlists.delete("tenant-a", "shared_name");
        Watchlists.delete("tenant-b", "shared_name");
    }

    @Test
    void keyExtractor_supportsTopLevelHostField() {
        // 修复点：keyField=host 过去只查 fields，导致以主机聚合的规则永不触发
        Rule r = new RuleSpec(Json.parseObject("""
                {"id":"T1","name":"主机聚合阈值","type":"threshold","severity":"HIGH",
                 "message":"{key} 命中 {count} 次","keyField":"host","threshold":3,"window":"60s",
                 "match":[{"field":"source","op":"eq","value":"edr"}]}
                """)).toRule();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            r.accept(ev(t0.plusSeconds(i), "edr", "srv-01", Map.of()));
        }
        List<Alert> alerts = r.drain();
        assertEquals(1, alerts.size(), "keyField=host 应能取到事件顶层的 host");
        assertEquals("srv-01", alerts.get(0).entity());
    }

    @Test
    void riskScorer_ranksIntelBackedCredentialDumpAboveNoisyScan() {
        RiskScorer.Score dump = RiskScorer.score(Severity.HIGH, "T1003", 2, 3, 3);
        RiskScorer.Score scan = RiskScorer.score(Severity.HIGH, "T1046", 0, 0, 0);
        assertTrue(dump.score() > scan.score(),
                "同为 HIGH，命中情报的凭据转储必须排在扫描噪声之前");
        assertEquals("CRITICAL", dump.level());
        assertTrue(dump.score() <= 100, "总分必须截断在 100 以内");
        // 分项拆解必须完整，评分要对分析师可解释
        assertEquals(List.of("severity", "tactic", "intel", "frequency", "asset"),
                List.copyOf(dump.breakdown().keySet()));
        assertFalse(scan.breakdown().isEmpty());
    }
}
