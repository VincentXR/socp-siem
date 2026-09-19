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
     * Roles the platform can actually issue a session for. The gateway admits
     * exactly this set ({@code GatewayFilter.ROLES}) and the local/OIDC login
     * paths re-sign tokens with a role from it, so a role outside the set has no
     * issuance path and must not carry defaults here.
     */
    public static final Set<String> ISSUABLE_ROLES = Set.of("admin", "analyst", "viewer");

    /**
     * Role defaults are the minimum grant set of an issuable role. Approval
     * authority ({@code soar:approve}) belongs to {@code admin} only; a
     * dedicated approver identity must be modelled as an IdP role carrying an
     * explicit {@code permissions} claim (see docs/soar-design.md §13 for the
     * advisory per-role suggestion), never as an unissuable built-in default.
     */
    public static Set<String> roleDefaults(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        if ("admin".equals(normalized)) {
            return Arrays.stream(values()).map(Permission::wireName).collect(Collectors.toUnmodifiableSet());
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
