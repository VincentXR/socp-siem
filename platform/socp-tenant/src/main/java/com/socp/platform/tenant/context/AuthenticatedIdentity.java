package com.socp.platform.tenant.context;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable identity produced by the verified authentication boundary.
 *
 * <p>Business code must use this value instead of forwarding identity headers
 * or depending on Spring Security. The latter is intentionally optional in
 * this platform so servlet services can use the same lightweight JWT contract.</p>
 */
public record AuthenticatedIdentity(
        String subject,
        String tenantId,
        String role,
        Set<String> permissions,
        Set<String> groups,
        Kind kind
) {
    public enum Kind {
        USER,
        SERVICE,
        COLLECTOR,
        METRICS,
        DEV
    }

    public AuthenticatedIdentity {
        subject = normalizeRequired(subject, "subject");
        tenantId = normalizeRequired(tenantId, "tenantId");
        role = normalize(role);
        permissions = normalizedSet(permissions, false);
        groups = normalizedSet(groups, true);
        kind = kind == null ? Kind.USER : kind;
    }

    /** Authorities used by approval policy checks without Spring Security. */
    public Set<String> authorities() {
        Set<String> result = new LinkedHashSet<>();
        if (role != null && !role.isBlank()) result.add("ROLE_" + role.toUpperCase(Locale.ROOT));
        for (String group : groups) result.add("GROUP_" + group.toUpperCase(Locale.ROOT));
        result.addAll(permissions);
        return Set.copyOf(result);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private static Set<String> normalizedSet(Set<String> values, boolean upperCase) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            result.add(upperCase ? value.trim().toUpperCase(Locale.ROOT) : value.trim().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }
}
