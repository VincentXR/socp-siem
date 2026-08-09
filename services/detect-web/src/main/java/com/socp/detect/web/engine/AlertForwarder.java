package com.socp.detect.web.engine;

import com.socp.detect.web.store.RuleSpecStore;
import com.socp.detect.web.ueba.EntityRiskStore;
import com.socp.detect.web.util.Http;
import com.socp.rule.model.Alert;
import com.socp.rule.score.RiskScorer;
import org.springframework.beans.factory.annotation.Value;
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
 */
@Component
public class AlertForwarder {

    private final RuleSpecStore ruleStore;
    private final EntityRiskStore riskStore;

    @Value("${socp.alert.url:http://localhost:18080}")
    private String ssaUrl;

    @Value("${socp.soar.url:http://localhost:18083}")
    private String soarUrl;

    public AlertForwarder(RuleSpecStore ruleStore, EntityRiskStore riskStore) {
        this.ruleStore = ruleStore;
        this.riskStore = riskStore;
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
        Http.post(ssaUrl + "/alert-web/api/alarms", json, 3000);
        // SOAR 联动：best-effort 评估启用的剧本（命中则执行 NOTIFY/CASE/webhook 等动作）
        Http.post(soarUrl + "/soar-web/api/v1/playbooks/evaluate", json, 3000);
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
