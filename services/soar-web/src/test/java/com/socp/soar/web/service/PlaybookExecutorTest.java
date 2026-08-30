package com.socp.soar.web.service;

import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.persistence.store.PlaybookStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * SOAR 剧本执行器测试：触发条件评估 + 动作编排 + 重试/补偿 + 定时解析。
 *
 * <p>真实通知动作使用 mock ServiceCall；未接入动作必须显式失败或模拟，不能再走默认 success 分支。
 */
@ExtendWith(MockitoExtension.class)
class PlaybookExecutorTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Mock
    private PlaybookStore store;

    @Mock
    private NotifyClient notifyClient;

    @Mock
    private IncidentClient incidentClient;

    @Mock
    private SocpHttpClient http;

    @Mock
    private TemporalExecutor temporalExecutor;

    @Mock
    private ApprovalService approvalService;

    @InjectMocks
    private PlaybookExecutor executor;

    private static Playbook enabled(String trigger, List<String> actions) {
        return Playbook.create("t", trigger, actions, true);
    }

    private static ServiceCall successfulNotify() {
        return new ServiceCall(SocpService.NOTIFY, "http://notify", true,
                200, "{\"dispatched\":1,\"failed\":0}", null, 1, false, 1);
    }

    @Test
    void severityTriggerFiresWhenAlarmIsAtOrAboveThreshold() {
        given(store.list()).willReturn(List.of(
                enabled("告警 severity >= HIGH 且实体为 IP", List.of("通知值班群", "notify security"))));
        given(notifyClient.notifyAlert(any())).willReturn(successfulNotify());
        Map<String, Object> out = executor.evaluate(Map.of("id", "AL-1", "ruleId", "X",
                "severity", "CRITICAL", "entity", "1.2.3.4"));
        assertEquals("AL-1", out.get("alarmId"));
        assertEquals(1, out.get("triggered"), "仅 severity>=HIGH 的启用剧本应命中");
        List<?> pbs = (List<?>) out.get("playbooks");
        List<?> results = (List<?>) ((Map<?, ?>) pbs.get(0)).get("results");
        assertEquals(2, results.size(), "两个动作都应被编排执行");
        assertTrue(results.stream().allMatch(r -> "executed".equals(((Map<?, ?>) r).get("status"))));
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
        Playbook pb = Playbook.create("手动剧本", "X", List.of("通知值班群", "notify asset"), true);
        given(store.get(pb.id())).willReturn(pb);
        given(notifyClient.notifyAlert(any())).willReturn(successfulNotify());
        Map<String, Object> r = executor.runById(pb.id(), Map.of("entity", "1.2.3.4"));
        List<?> results = (List<?>) r.get("results");
        assertEquals(2, results.size());
        assertEquals("executed", ((Map<?, ?>) results.get(0)).get("status"));
    }

    @Test
    void highRiskManualTriggerCreatesApprovalAndDoesNotExecuteActions() {
        Playbook pb = Playbook.create("封禁", "manual", List.of("firewall-block"), true);
        given(store.get(pb.id())).willReturn(pb);
        given(approvalService.request(any(String.class), any(Map.class), any(String.class), any(String.class)))
                .willReturn(Map.of("approvalId", "APR-1", "status", "PENDING"));
        executor.setApprovalService(approvalService);

        Map<String, Object> result = executor.runById(pb.id(), Map.of("host", "web-1"));

        assertEquals("APPROVAL_REQUIRED", result.get("status"));
        assertEquals("APR-1", result.get("approvalId"));
        verify(approvalService).request(any(String.class), any(Map.class), any(String.class), any(String.class));
        verify(temporalExecutor, never()).run(any(), any());
    }

    @Test
    void failedActionTriggersCompensation() {
        Playbook pb = Playbook.create("补偿剧本", "AUTH-BRUTE",
                List.of("http://127.0.0.1:1/not-exist", // 端口 1 拒绝连接 → 必然失败
                        "补偿:notify rollback"), true);
        given(store.get(pb.id())).willReturn(pb);
        given(notifyClient.notifyAlert(any())).willReturn(successfulNotify());
        Map<String, Object> r = executor.runById(pb.id(), Map.of("entity", "5.6.7.8"));
        List<?> results = (List<?>) r.get("results");
        assertEquals("failed", ((Map<?, ?>) results.get(0)).get("status"));
        assertEquals("executed", ((Map<?, ?>) results.get(1)).get("status"), "补偿动作应执行");
    }

    @Test
    void failedActionSkipsSubsequentActionsExceptCompensation() {
        Playbook pb = Playbook.create("跳过剧本", "WATCH-BLOCKED-IP",
                List.of("http://127.0.0.1:1/boom", // 失败
                        "通知值班群",       // 主动作：应跳过
                        "补偿:notify evidence"), true);
        given(store.get(pb.id())).willReturn(pb);
        given(notifyClient.notifyAlert(any())).willReturn(successfulNotify());
        Map<String, Object> r = executor.runById(pb.id(), Map.of());
        List<?> results = (List<?>) r.get("results");
        assertEquals("failed", ((Map<?, ?>) results.get(0)).get("status"));
        assertEquals("skipped", ((Map<?, ?>) results.get(1)).get("status"), "失败后主动作跳过");
        assertEquals("executed", ((Map<?, ?>) results.get(2)).get("status"), "补偿执行");
    }

    @Test
    void unknownActionFailsWithExplicitErrorInsteadOfDefaultSuccess() {
        Map<String, Object> result = executor.executeAction("完全未知的动作", Map.of(), false);

        assertEquals("failed", result.get("status"));
        assertEquals("unknown", result.get("actionType"));
        assertEquals("UNSUPPORTED_ACTION", result.get("errorCode"));
        assertEquals("NOT_EXECUTED", result.get("mode"));
    }

    @Test
    void legacyFirewallActionIsNotSilentlySimulated() {
        Map<String, Object> result = executor.executeAction("firewall-block", Map.of(), false);

        assertEquals("failed", result.get("status"));
        assertEquals("firewall_block", result.get("actionType"));
        assertEquals("CONNECTOR_NOT_CONFIGURED", result.get("errorCode"));
    }

    @Test
    void explicitSimulationHasDistinctStatusAndStableIdempotencyKey() {
        ReflectionTestUtils.setField(executor, "simulationEnabled", true);
        Map<String, Object> alarm = Map.of("id", "AL-SIM-1", "playbookId", "PB-1");

        Map<String, Object> first = executor.executeAction("simulate:firewall-block", alarm, false, 2);
        Map<String, Object> second = executor.executeAction("simulate:firewall-block", alarm, false, 2);

        assertEquals("simulated", first.get("status"));
        assertEquals("SIMULATED", first.get("mode"));
        assertEquals(first.get("idempotencyKey"), second.get("idempotencyKey"));
        assertTrue(String.valueOf(first.get("idempotencyKey")).startsWith("soar-"));
    }

    @Test
    void notificationWithoutSuccessfulDeliveryReceiptFails() {
        given(notifyClient.notifyAlert(any())).willReturn(new ServiceCall(
                SocpService.NOTIFY, "http://notify", true, 200, "{\"dispatched\":1,\"failed\":1}",
                null, 1, false, 1));

        Map<String, Object> result = executor.executeAction("notify security", Map.of("id", "AL-NOTIFY-1"), false);

        assertEquals("failed", result.get("status"));
        assertEquals("DOWNSTREAM_DELIVERY_FAILED", result.get("errorCode"));
        assertEquals("EXECUTED", result.get("mode"));
    }

    @Test
    void simulatedActionIsNotReportedAsExecutedWhenSimulationIsDisabled() {
        Map<String, Object> result = executor.executeAction("tag compromised-host", Map.of(), false);

        assertEquals("failed", result.get("status"));
        assertEquals("tag", result.get("actionType"));
        assertEquals("SIMULATION_DISABLED", result.get("errorCode"));
        assertEquals("NOT_EXECUTED", result.get("mode"));
    }

    @Test
    void productionProfileRejectsSimulationEvenWhenDevelopmentFlagIsEnabled() {
        ReflectionTestUtils.setField(executor, "simulationEnabled", true);
        ReflectionTestUtils.setField(executor, "activeProfiles", "prod");

        Map<String, Object> result = executor.executeAction("simulate:firewall-block", Map.of(), false);

        assertEquals("failed", result.get("status"));
        assertEquals("SIMULATION_DISABLED", result.get("errorCode"));
        assertEquals("NOT_EXECUTED", result.get("mode"));
    }

    @Test
    void scheduleHourParsing() {
        assertEquals(3, ScheduledPlaybookRunner.parseHour("每天 03:00"));
        assertEquals(15, ScheduledPlaybookRunner.parseHour("定时 15:30 巡检"));
        assertEquals(2, ScheduledPlaybookRunner.parseHour("每天 2 点"));
        assertEquals(LocalTime.of(15, 30), ScheduledPlaybookRunner.parseTime("定时 15:30 巡检"));
        assertNull(ScheduledPlaybookRunner.parseTime("schedule 24:00"));
        assertNull(ScheduledPlaybookRunner.parseTime("schedule 12:60"));
        assertNull(ScheduledPlaybookRunner.parseHour("无时间"));
    }
}
