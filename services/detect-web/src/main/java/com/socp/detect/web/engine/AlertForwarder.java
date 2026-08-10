package com.socp.detect.web.engine;

import com.socp.detect.web.store.RuleSpecStore;
import com.socp.detect.web.ueba.EntityRiskStore;
import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SoarClient;
import com.socp.rule.model.Alert;
import com.socp.rule.score.RiskScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警转发器：把规则引擎产出的告警 best-effort 推送到 ALERT（落 t_alarm），
 * 附上规则的 MITRE ATT&CK 技术 ID，并在转发前完成两件事：
 * <ol>
 *   <li>把告警计入实体风险画像（UEBA 看板数据源）；</li>
 *   <li>算出该告警的威胁评分 riskScore 一并下发，ALERT 侧可直接按分排序处置。</li>
 * </ol>
 *
 * <p><b>best-effort ≠ 静默</b>：转发失败不会阻塞检测热路径，但一定会打 WARN。
 * 这条链路一旦断了，就意味着「攻击被检出、告警却没进库」，是最不能无声失败的地方。
 * 地址由 {@link AlertClient} / {@link SoarClient} 统一解析（支持 {@code SOCP_ALERT_URL} 等环境变量）。
 */
@Component
public class AlertForwarder {

    private static final Logger log = LoggerFactory.getLogger(AlertForwarder.class);

    private final RuleSpecStore ruleStore;
    private final EntityRiskStore riskStore;
    private final AlertClient alertClient;
    private final SoarClient soarClient;

    public AlertForwarder(RuleSpecStore ruleStore, EntityRiskStore riskStore,
                          AlertClient alertClient, SoarClient soarClient) {
        this.ruleStore = ruleStore;
        this.riskStore = riskStore;
        this.alertClient = alertClient;
        this.soarClient = soarClient;
    }

    /** 异步无关地转发（调用方在引擎虚拟线程里，避免在热路径阻塞）。 */
    public void forward(Alert a) {
        Thread.startVirtualThread(() -> doForward(a));
    }

    private void doForward(Alert a) {
        Map<String, Object> spec = ruleStore.get(a.ruleId());
        String mitre = spec != null ? String.valueOf(spec.getOrDefault("mitre", "")) : "";
        if (mitre == null || mitre.isBlank() || "null".equals(mitre)) mitre = null;

        // 威胁评分：情报命中由 ALERT 富化后二次修正，此处先给出检测侧初评
        RiskScorer.Score score = riskStore.record(
                a.entity(), a.severity(), mitre, a.ruleId(), a.ruleName(), 0);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", a.id());
        payload.put("ruleId", a.ruleId());
        payload.put("ruleName", a.ruleName());
        payload.put("severity", a.severity().name());
        payload.put("message", a.message());
        payload.put("entity", a.entity());
        payload.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(a.timestamp()));
        payload.put("riskScore", score.score());
        if (mitre != null) payload.put("mitre", mitre);
        String json = toJson(payload);

        // 主链路：告警落库。失败 = 检出的攻击丢失，必须显式告警到日志（客户端已打 WARN，
        // 这里再补一条带业务上下文的，运维一眼能看出丢的是哪条规则、哪个实体）
        ServiceCall forwarded = alertClient.forwardAlarm(json);
        if (!forwarded.ok()) {
            log.warn("告警转发失败，该告警未进入 alert-web：alertId={} ruleId={} ruleName={} entity={} severity={} 原因={}",
                    a.id(), a.ruleId(), a.ruleName(), a.entity(), a.severity(), forwarded.failureReason());
        }

        // SOAR 联动：best-effort 评估启用的剧本（命中则执行 NOTIFY/CASE/webhook 等动作）。
        // 失败只影响自动化响应，不影响告警本身，交给客户端统一记 WARN 即可。
        soarClient.evaluate(json);
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
