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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

/**
 * SOAR 剧本执行器单测：只覆盖“触发条件评估 + 动作编排”这层纯逻辑。
 *
 * <p>刻意不使用含 http 的动作，因为 {@code PlaybookExecutor#run} 对含 "http" 的动作
 * 会发真实 webhook；单测环境无外部依赖，用普通动作走“executed”分支。
 */
@ExtendWith(MockitoExtension.class)
class PlaybookExecutorTest {

    @Mock
    private PlaybookStore store;

    @InjectMocks
    private PlaybookExecutor executor;

    private static final Playbook BLOCK_HIGH = Playbook.create(
            "高危告警自动封禁", "告警 severity >= HIGH 且实体为 IP",
            List.of("查询资产归属", "下发防火墙封禁"), true);

    private static final Playbook DAILY_SCAN = Playbook.create(
            "每日安全巡检", "定时 每天 03:00", List.of("汇总告警", "生成日报"), true);

    private static final Playbook DISABLED_LOW = Playbook.create(
            "低危留档（停用）", "告警 severity >= LOW", List.of("记录研判上下文"), false);

    private static final Playbook BRUTE_ISOLATE = Playbook.create(
            "暴力破解隔离主机", "AUTH-BRUTE-SUCCESS 关联告警",
            List.of("标记主机失陷", "网络隔离 (VLAN 迁移)"), true);

    @Test
    void severityTriggerFiresWhenAlarmIsAtOrAboveThreshold() {
        given(store.list()).willReturn(List.of(BLOCK_HIGH, DAILY_SCAN, DISABLED_LOW));

        Map<String, Object> out = executor.evaluate(Map.of(
                "id", "AL-1", "ruleId", "FW-SCAN", "severity", "high", "entity", "10.0.0.66"));

        assertEquals("AL-1", out.get("alarmId"));
        assertEquals(1, out.get("triggered"), "仅 severity>=HIGH 的启用剧本应命中");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pbs = (List<Map<String, Object>>) out.get("playbooks");
        assertEquals("高危告警自动封禁", pbs.get(0).get("playbook"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) pbs.get(0).get("results");
        assertEquals(2, results.size(), "两个动作都应被编排执行");
        assertTrue(results.stream().allMatch(r -> "executed".equals(r.get("status"))));
        assertEquals("下发防火墙封禁", results.get(1).get("action"));
    }

    @Test
    void severityBelowThresholdTriggersNothing() {
        given(store.list()).willReturn(List.of(BLOCK_HIGH, DAILY_SCAN, DISABLED_LOW));

        Map<String, Object> out = executor.evaluate(Map.of(
                "id", "AL-2", "ruleId", "FW-SCAN", "severity", "medium"));

        assertEquals(0, out.get("triggered"), "MEDIUM 低于 HIGH 阈值；定时剧本不走告警路径；停用剧本跳过");
    }

    @Test
    void ruleIdSubstringTriggerFiresRegardlessOfSeverity() {
        given(store.list()).willReturn(List.of(BRUTE_ISOLATE, DAILY_SCAN));

        Map<String, Object> out = executor.evaluate(Map.of(
                "id", "AL-3", "ruleId", "AUTH-BRUTE-SUCCESS", "severity", "low"));

        assertEquals(1, out.get("triggered"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pbs = (List<Map<String, Object>>) out.get("playbooks");
        assertEquals("暴力破解隔离主机", pbs.get(0).get("playbook"));
    }

    @Test
    void scheduledPlaybooksNeverFireOnAlarmPath() {
        given(store.list()).willReturn(List.of(DAILY_SCAN));

        Map<String, Object> out = executor.evaluate(Map.of(
                "id", "AL-4", "ruleId", "巡检", "severity", "critical"));

        assertEquals(0, out.get("triggered"), "含“定时”的触发条件由调度器驱动，不应被告警触发");
    }

    @Test
    void executionsRecordEveryTriggeredRun() {
        given(store.list()).willReturn(List.of(BLOCK_HIGH));

        assertTrue(executor.executions().isEmpty(), "初始无执行历史");

        executor.evaluate(Map.of("id", "AL-5", "ruleId", "MAL-C2", "severity", "critical"));
        executor.evaluate(Map.of("id", "AL-6", "ruleId", "MAL-C2", "severity", "critical"));

        List<Map<String, Object>> history = executor.executions();
        assertEquals(2, history.size());
        assertEquals(BLOCK_HIGH.id(), history.get(0).get("playbookId"));
        assertTrue(history.get(0).containsKey("ts"), "执行历史应带时间戳");
    }
}
