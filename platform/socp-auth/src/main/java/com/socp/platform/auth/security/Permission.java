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
    SOAR_APPROVE("soar:approve"),
    SOAR_EXECUTE("soar:execute"),
    TENANT_ADMIN("tenant:admin");

    private final String wireName;

    Permission(String wireName) { this.wireName = wireName; }

    public String wireName() { return wireName; }

    public static Set<String> roleDefaults(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if ("admin".equals(normalized)) {
            return Arrays.stream(values()).map(Permission::wireName).collect(Collectors.toUnmodifiableSet());
        }
        if ("analyst".equals(normalized)) {
            return Set.of(ALARM_READ.wireName, ALARM_TRIAGE.wireName, CASE_WRITE.wireName,
                    SOAR_EXECUTE.wireName);
        }
        if ("viewer".equals(normalized)) return Set.of(ALARM_READ.wireName);
        return Set.of();
    }
}
