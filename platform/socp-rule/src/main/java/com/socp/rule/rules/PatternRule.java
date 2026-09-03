package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 模式规则：单事件即触发。当事件满足谓词（如严重级别、字段正则、关键字）立即告警。
 * 典型场景：出现特权提升、Web 攻击特征、severity=CRITICAL 等。由 com.siem 迁移。
 */
public final class PatternRule extends AbstractRule {

    private final Predicate<SecurityEvent> matcher;
    private final Severity severity;
    private final String titleTemplate;
    private final String messageTemplate;

    public PatternRule(String id, String name,
                       Predicate<SecurityEvent> matcher,
                       Severity severity, String messageTemplate) {
        this(id, name, matcher, severity, name, messageTemplate);
    }

    public PatternRule(String id, String name,
                       Predicate<SecurityEvent> matcher,
                       Severity severity, String titleTemplate,
                       String messageTemplate) {
        super(id, name);
        this.matcher = matcher;
        this.severity = severity;
        this.titleTemplate = titleTemplate;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        if (!matcher.test(event)) return;
        String title = AlertTemplateRenderer.render(titleTemplate, event, Map.of());
        String msg = AlertTemplateRenderer.render(messageTemplate, event, Map.of());
        String entity = event.get("src_ip");
        if (entity == null) entity = event.host();
        emit(new Alert(id, name, severity, title, msg, entity, List.of(event)));
    }
}
