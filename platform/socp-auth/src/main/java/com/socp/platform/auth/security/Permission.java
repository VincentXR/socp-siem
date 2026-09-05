package com.socp.platform.auth.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable permission vocabulary carried in JWT {@code permissions} claims. */
public enum Permission {
    ALARM_READ("alarm:read"),
    ALARM_TRIAGE("alarm:triage"),
    CASE_WRITE("case:write"),
    RULE_ACTIVATE("rule:activate"),
    SOAR_VIEW("soar:view"),
    SOAR_EDIT("soar:edit"),
    SOAR_PUBLISH("soar:publish"),
    SOAR_EXECUTE("soar:execute"),
    SOAR_APPROVE("soar:approve"),
    SOAR_TASK_COMPLETE("soar:task:complete"),
    SOAR_CONNECTIONS_VIEW("soar:connections:view"),
    SOAR_CONNECTIONS_MANAGE("soar:connections:manage"),
    SOAR_OPERATIONS("soar:operations"),
    TENANT_ADMIN("tenant:admin");

    private final String wireName;

    Permission(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    /**
     * Role defaults follow the SOAR 2.0 design (docs/soar-2.0-design.md §13.1):
     * view -> viewer/analyst/admin; edit/execute/task:complete/connections:view ->
     * analyst/admin; publish/operations/connections:manage -> admin; approve -> approver/admin.
     */
    public static Set<String> roleDefaults(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if ("admin".equals(normalized)) {
            return Arrays.stream(values()).map(Permission::wireName).collect(Collectors.toUnmodifiableSet());
        }
        if ("approver".equals(normalized)) {
            return Set.of(ALARM_READ.wireName, SOAR_VIEW.wireName, SOAR_APPROVE.wireName,
                    SOAR_TASK_COMPLETE.wireName);
        }
        if ("analyst".equals(normalized)) {
            return Set.of(ALARM_READ.wireName, ALARM_TRIAGE.wireName, CASE_WRITE.wireName,
                    SOAR_VIEW.wireName, SOAR_EDIT.wireName, SOAR_EXECUTE.wireName,
                    SOAR_TASK_COMPLETE.wireName, SOAR_CONNECTIONS_VIEW.wireName);
        }
        if ("viewer".equals(normalized)) return Set.of(ALARM_READ.wireName, SOAR_VIEW.wireName);
        return Set.of();
    }
}
