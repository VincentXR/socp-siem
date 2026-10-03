package com.socp.gateway.oidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.auth.security.Permission;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Bounded mapping of verified IdP claims into the platform's authority namespaces. */
public final class OidcClaimMapping {
    private static final Set<String> PERMISSIONS = Arrays.stream(Permission.values())
            .map(Permission::wireName).collect(Collectors.toUnmodifiableSet());
    private OidcClaimMapping() { }

    public static Set<String> groups(Map<String, Object> claims, String mappings) {
        Set<String> result = new LinkedHashSet<>();
        add(result, claims.get("groups"));
        add(result, claims.get("group"));
        applyMappings(result, mappings);
        return result.stream().map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Set<String> permissions(Map<String, Object> claims, String mappings) {
        Set<String> result = new LinkedHashSet<>();
        add(result, claims.get("permissions"));
        applyMappings(result, mappings);
        return result.stream().map(value -> value.toLowerCase(Locale.ROOT)).filter(PERMISSIONS::contains)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void applyMappings(Set<String> values, String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return;
        try {
            Map<String, Object> mappings = new ObjectMapper().readValue(json, new TypeReference<>() { });
            Set<String> mapped = new LinkedHashSet<>();
            for (String source : values) {
                if (mappings.containsKey(source)) add(mapped, mappings.get(source));
                else add(mapped, source);
            }
            values.clear();
            values.addAll(mapped);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid OIDC authority mapping configuration", invalid);
        }
    }

    private static void add(Set<String> result, Object claim) {
        if (claim == null) return;
        if (claim instanceof Collection<?> list) {
            if (list.size() > 256) throw new IllegalArgumentException("Too many OIDC authorities");
            for (Object value : list) {
                if (!(value instanceof String)) throw new IllegalArgumentException("Invalid OIDC authority claim");
                add(result, value);
            }
            return;
        }
        if (!(claim instanceof String value)) throw new IllegalArgumentException("Invalid OIDC authority claim");
        for (String item : value.split("[,\\s]+")) {
            if (item.isBlank()) continue;
            if (item.length() > 128 || result.size() >= 256) throw new IllegalArgumentException("OIDC authority limit exceeded");
            result.add(item);
        }
    }
}
