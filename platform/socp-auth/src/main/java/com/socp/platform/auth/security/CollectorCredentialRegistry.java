package com.socp.platform.auth.security;

import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves collector credentials into a trusted collector and tenant identity.
 *
 * <p>The compact configuration format is deliberately environment-friendly:
 * {@code collector-id|tenant-id|secret;another-id|tenant-id|secret}.  Secrets
 * are never logged and comparisons are constant-time.  A single legacy ingest
 * token remains available for local development, but production validation
 * requires at least one registered collector credential.</p>
 */
public class CollectorCredentialRegistry {

    public static final String COLLECTOR_ID_ATTRIBUTE =
            CollectorCredentialRegistry.class.getName() + ".collectorId";

    private final SocpSecurityProperties properties;
    private final List<Credential> fixedCredentials;

    @Autowired
    public CollectorCredentialRegistry(SocpSecurityProperties properties) {
        this.properties = properties;
        this.fixedCredentials = List.of();
    }

    CollectorCredentialRegistry(String encoded) {
        this.properties = null;
        this.fixedCredentials = parse(encoded);
    }

    @PostConstruct
    void validateConfiguration() {
        // Fail fast on malformed configuration instead of waiting for the
        // first collector request to discover it. Unit tests that mutate
        // SocpSecurityProperties still resolve dynamically below.
        if (properties != null) parse(properties.getCollectorCredentials());
    }

    /** Returns the trusted identity for a bearer secret, if one is configured. */
    public Optional<Identity> authenticate(String secret) {
        if (secret == null || secret.isBlank()) return Optional.empty();
        for (Credential credential : credentials()) {
            if (constantTimeEquals(credential.secret(), secret)) {
                return Optional.of(new Identity(credential.id(), credential.tenantId()));
            }
        }
        return Optional.empty();
    }

    public boolean isConfigured() {
        return !credentials().isEmpty();
    }

    private List<Credential> credentials() {
        return properties == null ? fixedCredentials : parse(properties.getCollectorCredentials());
    }

    private static List<Credential> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<Credential> result = new ArrayList<>();
        for (String item : encoded.split(";")) {
            String value = item.trim();
            if (value.isBlank()) continue;
            String[] parts = value.split("\\|", 3);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException(
                        "socp.security.collector-credentials must use id|tenant|secret entries");
            }
            String id = parts[0].trim();
            String tenant = parts[1].trim();
            String secret = parts[2].trim();
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalStateException("collector credential id is invalid: " + id);
            }
            if (!TenantContext.isValid(tenant)) {
                throw new IllegalStateException("collector credential tenant is invalid: " + tenant);
            }
            if (result.stream().anyMatch(existing -> existing.id().equals(id))) {
                throw new IllegalStateException("duplicate collector credential id: " + id);
            }
            if (result.stream().anyMatch(existing -> constantTimeEquals(existing.secret(), secret))) {
                throw new IllegalStateException("duplicate collector credential secret");
            }
            result.add(new Credential(id, tenant, secret));
        }
        return List.copyOf(result);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private record Credential(String id, String tenantId, String secret) {
    }

    public record Identity(String collectorId, String tenantId) {
    }
}
