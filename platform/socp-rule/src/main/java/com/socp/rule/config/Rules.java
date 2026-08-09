package com.socp.rule.config;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.CorrelationRule;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.Rule;
import com.socp.rule.rules.ThresholdRule;
import com.socp.rule.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 规则来源（由 com.siem 迁移）：代码静态定义 defaultRules 便于阅读与单测；
 * 运营侧规则 CRUD 由 DETECT 服务提供（detect-web 落 PG，构造相同的 Rule 类型）。
 */
public final class Rules {

    private Rules() {
    }

    public static List<Rule> defaultRules() {
        return List.of(
                // 1) 阈值规则：同一源 IP 短时被大量拦截 => 扫描/探测
                new ThresholdRule(
                        "FW-SCAN",
                        "高频阻断（疑似扫描）",
                        e -> "firewall".equals(e.source()) && "block".equals(e.fields().get("action")),
                        e -> e.fields().get("src_ip"),
                        10, Duration.ofSeconds(30),
                        Severity.MEDIUM,
                        "源 {key} 在 {window} 内被拦截 {count} 次，疑似端口扫描/探测"),

                // 2) 阈值规则：同一源 IP 短时失败登录过多 => 暴力破解
                new ThresholdRule(
                        "AUTH-BRUTE",
                        "SSH 暴力破解",
                        e -> "auth".equals(e.source()) && contains(e, "Failed password"),
                        e -> e.fields().get("src_ip"),
                        5, Duration.ofSeconds(60),
                        Severity.HIGH,
                        "源 {key} 在 {window} 内失败登录 {count} 次，疑似暴力破解"),

                // 3) 模式规则：权限提升特征
                new PatternRule(
                        "AUTH-PRIVESC",
                        "权限提升",
                        e -> contains(e, "Privilege escalation") || contains(e, "sudo:") && e.severity().atLeast(Severity.HIGH),
                        Severity.CRITICAL,
                        "检测到权限提升行为：{msg} @ {host}"),

                // 4) 模式规则：Web 攻击特征
                new PatternRule(
                        "WEB-ATTACK",
                        "Web 攻击特征",
                        e -> "web".equals(e.source())
                                && (contains(e, "SQLi") || contains(e, "XSS")
                                || contains(e, "注入") || contains(e, "attack")),
                        Severity.HIGH,
                        "疑似 Web 攻击：{msg} @ {host}"),

                // 5) 关联规则：失败登录后紧接成功登录 => 暴力破解可能得手
                new CorrelationRule(
                        "AUTH-BRUTE-SUCCESS",
                        "暴力破解得手（关联）",
                        e -> e.fields().get("src_ip"),
                        List.of(
                                (Predicate<SecurityEvent>) e ->
                                        "auth".equals(e.source()) && contains(e, "Failed password"),
                                e -> "auth".equals(e.source()) && contains(e, "Accepted password")
                        ),
                        Duration.ofSeconds(120),
                        Severity.CRITICAL,
                        "源 {key} 在攻击链中疑似暴力破解得手：失败登录后出现了成功登录")
        );
    }

    private static boolean contains(SecurityEvent e, String kw) {
        String msg = e.get("msg");
        if (msg != null && msg.contains(kw)) return true;
        return e.raw() != null && e.raw().contains(kw);
    }

    /**
     * 从 JSON 配置文件加载规则集。文件顶层为规则数组，每条规则结构见 {@link RuleSpec}。
     * enabled=false 的规则被跳过。由 com.siem 迁移。
     */
    @SuppressWarnings("unchecked")
    public static List<Rule> fromConfig(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<?> arr = (List<?>) Json.parse(text);
        List<Rule> rules = new ArrayList<>();
        for (var o : arr) {
            RuleSpec spec = new RuleSpec((Map<String, Object>) o);
            if (!spec.enabled) continue; // 跳过被禁用的规则
            rules.add(spec.toRule());
        }
        return rules;
    }
}
