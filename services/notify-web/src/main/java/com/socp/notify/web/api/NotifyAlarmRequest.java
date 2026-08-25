package com.socp.notify.web.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded notification contract; connector-specific fields remain isolated in attributes. */
public class NotifyAlarmRequest {

    @NotBlank
    @Size(max = 255)
    private String id;
    @Size(max = 128)
    private String ruleId;
    @Size(max = 256)
    private String ruleName;
    @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO")
    @Size(max = 32)
    private String severity;
    @Size(max = 256)
    private String entity;
    @Size(max = 4096)
    private String message;
    @Size(max = 128)
    private String mitre;
    @Size(max = 64)
    private String occurredAt;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getMitre() { return mitre; }
    public void setMitre(String mitre) { this.mitre = mitre; }
    public String getOccurredAt() { return occurredAt; }
    public void setOccurredAt(String occurredAt) { this.occurredAt = occurredAt; }

    @JsonAnySetter
    public void addAttribute(String name, Object value) {
        if (attributes.size() >= 64 && !attributes.containsKey(name)) {
            throw new IllegalArgumentException("notification contains too many attributes");
        }
        attributes.put(name, value);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>(attributes);
        put(out, "id", id);
        put(out, "ruleId", ruleId);
        put(out, "ruleName", ruleName);
        put(out, "severity", severity);
        put(out, "entity", entity);
        put(out, "message", message);
        put(out, "mitre", mitre);
        put(out, "occurredAt", occurredAt);
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) out.put(key, value);
    }
}
