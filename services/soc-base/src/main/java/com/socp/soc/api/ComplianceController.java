package com.socp.soc.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合规模板 REST API（context-path /soc-base）。
 * 内置主流框架（PCI-DSS / HIPAA / ISO 27001 / GDPR）控制项，并将控制项映射到检测规则，
 * 结合当前启用规则计算合规覆盖率（大厂 SIEM 的 Compliance / Audit 看板能力）。
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    /** 框架 -> 控制项（id / 名称 / 建议映射的规则 ID 列表）。 */
    private static final Map<String, List<Control>> FRAMEWORKS = build();

    private static Map<String, List<Control>> build() {
        // 控制项映射的规则 ID 必须与 DETECT 内置规则（RuleSpecStore.SEED_JSON）保持一致，否则覆盖率恒为 0
        Map<String, List<Control>> m = new LinkedHashMap<>();
        m.put("PCI-DSS", List.of(
                new Control("PCI-10.2", "审计日志覆盖所有对象访问", List.of("AUTH-BRUTE", "AUTH-PRIVESC")),
                new Control("PCI-10.5", "审计日志防篡改", List.of("EVADE-LOGCLEAR")),
                new Control("PCI-10.6", "日志每日审查与告警", List.of("WEB-ATTACK", "FW-SCAN")),
                new Control("PCI-11.4", "入侵检测与防御", List.of("DOS-FLOOD", "LATERAL-RDP")),
                new Control("PCI-6.5", "安全开发（防注入/XSS）", List.of("WEB-ATTACK")),
                new Control("PCI-5.1", "恶意软件防护", List.of("RANSOM-ENCRYPT", "MAL-C2")),
                new Control("PCI-8.1", "身份识别与认证管理", List.of("AUTH-BRUTE-SUCCESS"))));
        m.put("HIPAA", List.of(
                new Control("HIPAA-164.312(b)", "审计控制", List.of("AUTH-BRUTE", "AUTH-PRIVESC", "EVADE-LOGCLEAR")),
                new Control("HIPAA-164.308(a)(1)", "安全事件评估与响应", List.of("WEB-ATTACK", "MAL-C2")),
                new Control("HIPAA-164.308(a)(5)", "恶意软件防护意识", List.of("RANSOM-ENCRYPT", "PHISH-MAIL")),
                new Control("HIPAA-164.312(a)", "访问控制", List.of("AUTH-BRUTE-SUCCESS", "LATERAL-RDP")),
                new Control("HIPAA-164.312(e)", "传输安全", List.of("EXFIL-LARGE"))));
        m.put("ISO27001", List.of(
                new Control("A.5.7", "威胁情报", List.of("MAL-C2", "PHISH-MAIL")),
                new Control("A.8.7", "防范恶意软件", List.of("RANSOM-ENCRYPT", "EXEC-SUSPICIOUS-SHELL")),
                new Control("A.8.8", "技术脆弱性管理", List.of("WEB-ATTACK")),
                new Control("A.8.15", "日志记录", List.of("AUTH-BRUTE", "AUTH-PRIVESC", "FW-SCAN")),
                new Control("A.8.16", "监视活动", List.of("MAL-C2", "EXFIL-LARGE")),
                new Control("A.8.20", "网络安全", List.of("DOS-FLOOD", "LATERAL-RDP"))));
        m.put("GDPR", List.of(
                new Control("GDPR-Art.25", "默认数据保护", List.of("AUTH-PRIVESC", "AUTH-BRUTE-SUCCESS")),
                new Control("GDPR-Art.32", "处理安全（加密/抗攻击）", List.of("EXFIL-LARGE", "MAL-C2", "RANSOM-ENCRYPT")),
                new Control("GDPR-Art.33", "个人数据泄露通知", List.of("EXFIL-LARGE", "WEB-ATTACK"))));
        // 等保 2.0（GB/T 22239-2019）三级 安全计算/区域边界 相关要求项
        m.put("等保2.0-三级", List.of(
                new Control("DJCP-8.1.2.3", "网络架构与边界防护", List.of("DOS-FLOOD", "FW-SCAN")),
                new Control("DJCP-8.1.3.2", "访问控制", List.of("AUTH-BRUTE-SUCCESS", "LATERAL-RDP")),
                new Control("DJCP-8.1.4.3", "安全审计", List.of("AUTH-BRUTE", "AUTH-PRIVESC", "EVADE-LOGCLEAR")),
                new Control("DJCP-8.1.4.4", "入侵防范", List.of("WEB-ATTACK", "EXEC-SUSPICIOUS-SHELL", "CRED-DUMP")),
                new Control("DJCP-8.1.4.5", "恶意代码防范", List.of("RANSOM-ENCRYPT", "MAL-C2", "PHISH-MAIL")),
                new Control("DJCP-8.1.4.6", "数据完整性与保密性", List.of("EXFIL-LARGE")),
                new Control("DJCP-8.1.5.4", "集中管控", List.of("PERSIST-TASK"))));
        return m;
    }

    @GetMapping("/frameworks")
    public Map<String, Object> frameworks() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (var e : FRAMEWORKS.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", e.getKey());
            f.put("controls", e.getValue());
            list.add(f);
        }
        out.put("frameworks", list);
        return out;
    }

    /**
     * 覆盖率：请求体 {"ruleIds": ["AUTH-BRUTE","SQL-INJECT"]}，
     * 返回每个框架哪些控制项已被现有规则覆盖。
     */
    @PostMapping("/coverage")
    public Map<String, Object> coverage(@Valid @RequestBody CoverageRequest request) {
        List<String> ruleIds = request.ruleIds();
        Set<String> have = Set.copyOf(ruleIds);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> result = new ArrayList<>();
        int totalControls = 0, coveredControls = 0;
        for (var e : FRAMEWORKS.entrySet()) {
            List<Map<String, Object>> controls = new ArrayList<>();
            for (Control c : e.getValue()) {
                boolean covered = c.ruleIds.stream().anyMatch(have::contains);
                if (covered) coveredControls++;
                totalControls++;
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("id", c.id);
                cm.put("name", c.name);
                cm.put("covered", covered);
                cm.put("mappedRules", c.ruleIds);
                controls.add(cm);
            }
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("framework", e.getKey());
            fm.put("controls", controls);
            long cov = controls.stream().filter(c -> (Boolean) c.get("covered")).count();
            fm.put("coverage", controls.isEmpty() ? 0 : (int) Math.round(100.0 * cov / controls.size()));
            result.add(fm);
        }
        out.put("byFramework", result);
        out.put("totalControls", totalControls);
        out.put("coveredControls", coveredControls);
        out.put("coverage", totalControls == 0 ? 0 : (int) Math.round(100.0 * coveredControls / totalControls));
        return out;
    }

    private record Control(String id, String name, List<String> ruleIds) {
    }
}
