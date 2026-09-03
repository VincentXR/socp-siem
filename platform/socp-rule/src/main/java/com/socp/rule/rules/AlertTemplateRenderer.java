package com.socp.rule.rules;

import com.socp.rule.model.SecurityEvent;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the small, deliberately logic-free alert template language.
 *
 * <p>Both the legacy {@code {host}} form and the preferred
 * {@code {{event.host}}} form are accepted.  Values come only from the
 * current event and the rule evaluation context; no expression language or
 * executable user input is evaluated.</p>
 */
public final class AlertTemplateRenderer {

    private static final Pattern TOKEN = Pattern.compile(
            "\\{\\{\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*}}"
                    + "|\\{\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*}");

    private AlertTemplateRenderer() {
    }

    public static String render(String template, SecurityEvent event,
                                Map<String, ?> evaluationContext) {
        if (template == null || template.isEmpty()) return "";

        Matcher matcher = TOKEN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            Object value = resolve(token, event, evaluationContext);
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static Object resolve(String token, SecurityEvent event,
                                  Map<String, ?> evaluationContext) {
        if (evaluationContext != null && evaluationContext.containsKey(token)) {
            return evaluationContext.get(token);
        }
        if (event == null) return null;

        if (token.startsWith("event.")) return eventValue(event, token.substring("event.".length()));
        if (token.startsWith("fields.")) return eventValue(event, token.substring("fields.".length()));
        return eventValue(event, token);
    }

    private static String eventValue(SecurityEvent event, String field) {
        return switch (field) {
            case "source" -> event.source();
            case "host" -> event.host();
            case "severity" -> event.severity() == null ? null : event.severity().name();
            case "raw" -> event.raw();
            case "msg", "message", "event.message" -> {
                String message = event.fields() == null ? null : event.fields().get("msg");
                yield message == null || message.isBlank() ? event.raw() : message;
            }
            default -> event.fields() == null ? null : event.fields().get(field);
        };
    }
}
