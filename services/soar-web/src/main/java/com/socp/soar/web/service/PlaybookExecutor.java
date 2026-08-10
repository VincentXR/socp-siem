package com.socp.soar.web.service;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.store.PlaybookStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 剧本执行器（SOAR 运行时）：收到告警后评估启用的剧本触发条件，命中则按 actions 执行。
 * 动作语义：
 *  - HTTP/URL 类 → 真实 webhook POST；
 *  - NOTIFY（通知） → 调 notify-web 告警通知；
 *  - CASE（建案）   → 调 incident-web 由告警自动建案；
 *  - TAG 标签      → 记录到执行结果（演示框架）；
 *  - 其他          → 以日志形式"执行"。
 */
@Service
public class PlaybookExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlaybookExecutor.class);

    /** 外部 webhook 超时：用户配置的地址不受控，给足 3s 但绝不无限等。 */
    private static final int WEBHOOK_TIMEOUT_MS = 3000;

    private final PlaybookStore store;
    private final List<Map<String, Object>> executions = new CopyOnWriteArrayList<>();
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SocpHttpClient http;

    public PlaybookExecutor(PlaybookStore store, NotifyClient notifyClient,
                            IncidentClient incidentClient, SocpHttpClient http) {
        this.store = store;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.http = http;
    }

    /** 按 ID 手动触发执行（忽略启用状态与触发条件）。 */
    public Map<String, Object> runById(String id, Map<String, Object> context) {
        Playbook pb = store.get(id);
        if (pb == null) {
            return Map.of("error", "playbook not found", "playbookId", id);
        }
        Map<String, Object> alarm = new LinkedHashMap<>(context);
        alarm.putIfAbsent("ruleId", pb.trigger());
        alarm.putIfAbsent("severity", "HIGH");
        alarm.putIfAbsent("id", "manual-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        return run(pb, alarm);
    }

    /** 评估并编排执行。返回本次触发的剧本与动作结果。 */
    public Map<String, Object> evaluate(Map<String, Object> alarm) {
        String ruleId = str(alarm, "ruleId");
        String severity = str(alarm, "severity").toUpperCase();
        int sevLevel = sevLevel(severity);
        List<Map<String, Object>> triggered = new ArrayList<>();
        for (Playbook pb : store.list()) {
            if (!pb.enabled()) continue;
            if (!matches(pb.trigger(), ruleId, severity, sevLevel)) continue;
            Map<String, Object> exec = run(pb, alarm);
            triggered.add(exec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alarmId", alarm.get("id"));
        out.put("triggered", triggered.size());
        out.put("playbooks", triggered);
        return out;
    }

    private static final int MAX_ATTEMPTS = 3; // 每个动作最多尝试次数（重试 2 次）

    private Map<String, Object> run(Playbook pb, Map<String, Object> alarm) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean previousFailed = false;
        for (String action : pb.actions()) {
            Map<String, Object> r = executeAction(action, alarm, previousFailed);
            results.add(r);
            // 补偿动作（前缀"补偿:"）只在主动作失败后执行；主动作失败会阻断后续主动作
            if (action.startsWith("补偿:") || action.startsWith("compensate:")) {
                previousFailed = false; // 补偿已执行，视为完成该阶段
            } else {
                boolean ok = "success".equals(r.get("status"));
                if (!ok) {
                    previousFailed = true; // 失败：后续只执行补偿动作
                } else {
                    previousFailed = false;
                }
            }
        }
        Map<String, Object> exec = new LinkedHashMap<>();
        exec.put("playbookId", pb.id());
        exec.put("playbook", pb.name());
        exec.put("trigger", pb.trigger());
        exec.put("results", results);
        exec.put("ts", Instant.now().toString());
        if (executions.size() > 200) executions.remove(0);
        executions.add(exec);
        return exec;
    }

    /** 执行单个动作（含失败重试）；activeFailed 为 true 时跳过主动作、只允许补偿动作。 */
    private Map<String, Object> executeAction(String action, Map<String, Object> alarm, boolean activeFailed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action", action);
        String a = action.toLowerCase();
        boolean isCompensate = a.startsWith("补偿:") || a.startsWith("compensate:");
        if (activeFailed && !isCompensate) {
            r.put("status", "skipped");
            r.put("reason", "前置动作失败，本动作被跳过（仅执行补偿）");
            return r;
        }
        // 尝试执行（含重试）
        Map<String, Object> attempt = attempt(action, a, alarm);
        if (!"success".equals(attempt.get("status"))) {
            int retries = 0;
            while (retries < MAX_ATTEMPTS - 1) {
                retries++;
                attempt = attempt(action, a, alarm);
                if ("success".equals(attempt.get("status"))) break;
            }
            if (!"success".equals(attempt.get("status"))) {
                attempt.put("retried", retries);
            }
        }
        r.putAll(attempt);
        return r;
    }

    private Map<String, Object> attempt(String action, String a, Map<String, Object> alarm) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            if (a.contains("http://") || a.contains("https://")) {
                fill(r, "webhook", http.postExternal(action, toJson(alarm), SocpHttpClient.JSON, WEBHOOK_TIMEOUT_MS));
            } else if (a.contains("notify") || a.contains("通知")) {
                fill(r, "notify-web", notifyClient.notifyAlert(toJson(alarm)));
            } else if (a.contains("case") || a.contains("建案")) {
                fill(r, "incident-web", incidentClient.createFromAlarm(toJson(alarm)));
            } else if (a.contains("tag")) {
                r.put("status", "success");
                r.put("tag", action.contains(" ") ? action.substring(action.indexOf(' ') + 1).trim() : action);
            } else {
                r.put("status", "success");
            }
        } catch (Exception e) {
            log.warn("剧本动作执行异常 action={} alarmId={} error={}: {}",
                    action, alarm.get("id"), e.getClass().getSimpleName(), e.getMessage());
            r.put("status", "failed");
            r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }

    /**
     * 把一次服务调用的结果落进剧本执行记录。
     *
     * <p>失败原因会同时写进执行记录（前端可见）和日志（{@code socp-client} 已记 WARN），
     * 不再出现「剧本显示 failed，但没人知道为什么」。
     */
    private static void fill(Map<String, Object> r, String target, ServiceCall call) {
        r.put("status", call.ok() ? "success" : "failed");
        r.put("httpStatus", call.status());
        r.put("target", target);
        r.put("costMs", call.durationMs());
        if (!call.ok()) {
            r.put("error", call.failureReason());
        }
    }

    private boolean matches(String trigger, String ruleId, String severity, int sevLevel) {
        if (trigger == null) return false;
        String t = trigger.toLowerCase();
        if (t.contains("定时") || t.contains("schedule")) return false; // 定时类由调度器触发，不在告警路径
        // 规则 ID 子串匹配
        if (ruleId != null && !ruleId.isBlank() && t.contains(ruleId.toLowerCase())) return true;
        // 严重级别匹配：触发含 ">= HIGH" 之类
        if (t.contains("severity") || t.contains("级别") || t.contains("高危")) {
            for (String lvl : new String[]{"CRITICAL", "HIGH", "MEDIUM", "LOW"}) {
                if (t.contains(lvl.toLowerCase())) {
                    return sevLevel >= sevLevel(lvl);
                }
            }
        }
        return false;
    }

    public List<Map<String, Object>> executions() {
        return List.copyOf(executions);
    }

    private static int sevLevel(String s) {
        return switch (s) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            case "INFO" -> 1;
            default -> 0;
        };
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }
}
