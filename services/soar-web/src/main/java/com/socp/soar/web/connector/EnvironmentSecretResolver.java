package com.socp.soar.web.connector;

import org.springframework.stereotype.Component;

import java.util.Optional;

/** Development-safe resolver for env:// and secret:// environment references. */
@Component
public class EnvironmentSecretResolver implements SecretResolver {
    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        String value = reference.trim();
        String key;
        if (value.startsWith("env://")) {
            key = value.substring("env://".length());
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) return Optional.empty();
            return Optional.ofNullable(System.getenv(key));
        }
        if (!value.startsWith("secret://")) return Optional.empty();
        String secretKey = value.substring("secret://".length());
        if (!secretKey.matches("[A-Za-z_][A-Za-z0-9_./-]{0,254}")) return Optional.empty();
        String direct = secretKey.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") ? secretKey : null;
        if (direct != null && System.getenv(direct) != null) return Optional.of(System.getenv(direct));
        String normalized = "SOAR_SECRET_" + secretKey.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
        return Optional.ofNullable(System.getenv(normalized));
    }
}
