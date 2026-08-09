package com.socp.soar.web.service;

import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.store.PlaybookStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

/**
 * SOAR 剧本执行器测试：触发条件评估 + 动作编排 + 重试/补偿 + 定时解析。
 *
 * <p>触发类测试用普通动作走本地"success"分支，避免单测发真实 webhook。
 */
@ExtendWith(MockitoExtension.class)
class PlaybookExecutorTest {

    @Mock
    private PlaybookStore store;

    @InjectMocks
    private PlaybookExecutor executor;

    private static Playbook enabled(String trigger, List<String> actions) {
        return Playbook.create("t", trigger, actions, true);
    }

    @Test
    void severityTriggerFiresWhenAlarmIsAtOrAboveThreshold() {
        given(store.list()).willReturn(List.of(
                enabled("告警 severity >= HIGH 且实体为 IP", List.of("查询资产归属", "下发防火墙封禁"))));
        Map<String, Object> out = executor.evaluate(Map.of("id", "AL-1", "ruleId", "X",
                "severity", "CRITICAL", "entity", "1.2.3.4"));
        assertEquals("AL-1", out.get("alarmId"));
        assertEquals(1, out.get("triggered"), "仅 severity>=HIGH 的启用剧本应命中");
        List<?> pbs = (List<?>) out.get("playbooks");
        List<?> results = (List<?>) ((Map<?, ?>) pbs.get(0)).get("results");
        assertEquals(2, results.size(), "两个动作都应被编排执行");
        assertTrue(results.stream().allMatch(r -> "success".equals(((Map<?, ?>) r).get("status"))));
    }

    @Test
    void severityBelowThresholdTriggersNothing() {
        given(store.list()).willReturn(List.of(
                enabled("告警 severity >= HIGH 且实体为 IP", List.of("查询资产归属")),
                enabled("定时 每天 03:00", List.of("汇总告警"))));
        Map<String, Object> out = executor.evaluate(Map.of("id", "AL-2", "ruleId", "X",
                "severity", "MEDIUM", "entity", "1.2.3.4"));
        assertEquals(0, out.get("triggered"), "MEDIUM 低于 HIGH 阈值；定时剧本不走告警路径");
    }

    @Test
    void ruleIdSubstringTriggerFiresRegardlessOfSeverity() {
        given(store.list()).willReturn(List.of(
                enabled("AUTH-BRUTE-SUCCESS 关联告警", List.of("标记主机失陷"))));
        Map<String, Object> out = executor.evaluate(Map.of("id", "AL-3", "ruleId", "AUTH-BRUTE-SUCCESS",
                "severity", "LOW", "entity", "9.9.9.9"));
        assertEquals(1, out.get("triggered"));
    }

    @Test
    void scheduledPlaybooksNeverFireOnAlarmPath() {
        given(store.list()).willReturn(List.of(
                enabled("定时 每天 03:00", List.of("汇总告警"))));
        Map<String, Object> out = executor.evaluate(Map.of("id", "AL-4", "ruleId", "X",
                "severity", "CRITICAL", "entity", "1.2.3.4"));
        assertEquals(0, out.get("triggered"), "含“定时”的触发条件由调度器驱动，不应被告警触发");
    }

    @Test
    void manualTriggerExecutesAllActions() {
        Playbook pb = Playbook.create("手动剧本", "X", List.of("查询资产归属", "标记主机失陷"), true);
        given(store.get(pb.id())).willReturn(pb);
        Map<String, Object> r = executor.runById(pb.id(), Map.of("entity", "1.2.3.4"));
        List<?> results = (List<?>) r.get("results");
        assertEquals(2, results.size());
        assertEquals("success", ((Map<?, ?>) results.get(0)).get("status"));
    }

    @Test
    void failedActionTriggersCompensation() {
        Playbook pb = Playbook.create("补偿剧本", "AUTH-BRUTE",
                List.of("http://127.0.0.1:1/not-exist", // 端口 1 拒绝连接 → 必然失败
                        "补偿:记录失败并回滚"), true);
        given(store.get(pb.id())).willReturn(pb);
        Map<String, Object> r = executor.runById(pb.id(), Map.of("entity", "5.6.7.8"));
        List<?> results = (List<?>) r.get("results");
        assertEquals("failed", ((Map<?, ?>) results.get(0)).get("status"));
        assertEquals("success", ((Map<?, ?>) results.get(1)).get("status"), "补偿动作应执行成功");
    }

    @Test
    void failedActionSkipsSubsequentActionsExceptCompensation() {
        Playbook pb = Playbook.create("跳过剧本", "WATCH-BLOCKED-IP",
                List.of("http://127.0.0.1:1/boom", // 失败
                        "通知值班群",       // 主动作：应跳过
                        "补偿:快照取证"), true);
        given(store.get(pb.id())).willReturn(pb);
        Map<String, Object> r = executor.runById(pb.id(), Map.of());
        List<?> results = (List<?>) r.get("results");
        assertEquals("failed", ((Map<?, ?>) results.get(0)).get("status"));
        assertEquals("skipped", ((Map<?, ?>) results.get(1)).get("status"), "失败后主动作跳过");
        assertEquals("success", ((Map<?, ?>) results.get(2)).get("status"), "补偿执行");
    }

    @Test
    void scheduleHourParsing() {
        assertEquals(3, ScheduledPlaybookRunner.parseHour("每天 03:00"));
        assertEquals(15, ScheduledPlaybookRunner.parseHour("定时 15:30 巡检"));
        assertEquals(2, ScheduledPlaybookRunner.parseHour("每天 2 点"));
        assertNull(ScheduledPlaybookRunner.parseHour("无时间"));
    }
}
