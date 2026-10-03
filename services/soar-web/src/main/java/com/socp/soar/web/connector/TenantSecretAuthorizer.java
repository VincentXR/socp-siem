package com.socp.soar.web.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-owned exact grants. Tenant-controlled connector fields never grant secret access. */
public final class TenantSecretAuthorizer {
    private final Map<String, Set<String>> grants;
    public TenantSecretAuthorizer(String json) {
        try {
            if (json != null && json.length() > 1_048_576) throw new IllegalArgumentException();
            Map<String, List<String>> parsed = new ObjectMapper().readValue(
                    json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { });
            if (parsed == null || parsed.size() > 10_000) throw new IllegalArgumentException();
            grants = parsed.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> {
                if (!com.socp.platform.tenant.context.TenantContext.isValid(entry.getKey())
                        || entry.getValue() == null || entry.getValue().size() > 256
                        || entry.getValue().stream().anyMatch(value -> !validReference(value))) throw new IllegalArgumentException();
                return Set.copyOf(entry.getValue());
            }));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid SOAR tenant secret grants configuration", invalid);
        }
    }
    public boolean allows(String tenant, String reference) {
        return tenant != null && validReference(reference)
                && grants.getOrDefault(tenant, Set.of()).contains(reference);
    }
    public static boolean validReference(String reference) {
        if (reference == null || reference.length() > 1024 || !reference.equals(reference.trim())) return false;
        if (reference.matches("env://[A-Za-z_][A-Za-z0-9_]{0,127}")) return true;
        if (reference.matches("secret://[A-Za-z_][A-Za-z0-9_./-]{0,254}")) return safeSegments(reference.substring(9));
        if (reference.matches("k8s://[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")) return safeSegments(reference.substring(6));
        if (reference.matches("vault://[A-Za-z0-9._/-]+#[A-Za-z0-9._-]+")) {
            String path = reference.substring(8, reference.indexOf('#'));
            return path.contains("/") && safeSegments(path);
        }
        return false;
    }
    private static boolean safeSegments(String path) {
        for (String value : path.split("/", -1)) {
            if (value.isBlank() || value.length() > 253 || value.equals(".") || value.equals("..")) return false;
        }
        return true;
    }
}
