package com.socp.soar.web.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Development-safe resolver for env:// and secret:// environment references. */
@Component
@ConditionalOnProperty(prefix = "socp.soar.secrets", name = "backend",
        havingValue = "env", matchIfMissing = true)
public class EnvironmentSecretResolver implements SecretResolver {
    private final TenantSecretAuthorizer authorizer;
    public EnvironmentSecretResolver() { this(new com.socp.soar.web.config.SoarSecretProperties()); }
    @org.springframework.beans.factory.annotation.Autowired
    public EnvironmentSecretResolver(com.socp.soar.web.config.SoarSecretProperties properties) {
        authorizer = new TenantSecretAuthorizer(properties.getTenantGrants());
    }
    @Override public boolean isAuthorized(String tenantId, String reference) { return authorizer.allows(tenantId, reference); }

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
