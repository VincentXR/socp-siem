package com.socp.soar.web.connector;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves {@code k8s://namespace/secret/key} from a projected Kubernetes
 * Secret volume.  The file is opened for every Activity lookup; Kubernetes
 * atomic-volume rotation therefore takes effect without a process restart or
 * a playbook republish.  Environment references remain supported as a
 * compatibility path during migration.
 */
@Component
@ConditionalOnProperty(prefix = "socp.soar.secrets", name = "backend", havingValue = "kubernetes")
public class KubernetesSecretResolver implements SecretResolver {
    private static final int MAX_SECRET_BYTES = 64 * 1024;
    private final Path root;
    private final boolean allowEnvironmentFallback;
    private final SecretResolver environment = new EnvironmentSecretResolver();

    @org.springframework.beans.factory.annotation.Autowired
    public KubernetesSecretResolver(SoarSecretProperties properties) {
        this(properties == null ? null : properties.getKubernetesMountPath(),
                properties == null || properties.isAllowEnvironmentFallback());
    }

    KubernetesSecretResolver(String mountPath) {
        this(mountPath, true);
    }

    KubernetesSecretResolver(String mountPath, boolean allowEnvironmentFallback) {
        String value = mountPath == null || mountPath.isBlank()
                ? "/var/run/secrets/socp" : mountPath.trim();
        try {
            root = Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            throw new IllegalStateException("SOAR Kubernetes secret mount path is invalid");
        }
        this.allowEnvironmentFallback = allowEnvironmentFallback;
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        String value = reference.trim();
        if (!value.startsWith("k8s://")) {
            return allowEnvironmentFallback ? environment.resolve(value) : Optional.empty();
        }

        String path = value.substring("k8s://".length());
        String[] segments = path.split("/", -1);
        if (segments.length != 3 || !segmentsAreSafe(segments)) return Optional.empty();
        Path candidate;
        try {
            candidate = root.resolve(segments[0]).resolve(segments[1]).resolve(segments[2])
                    .normalize();
        } catch (InvalidPathException failure) {
            return Optional.empty();
        }
        if (!candidate.startsWith(root)) return Optional.empty();
        try {
            if (!Files.isRegularFile(candidate)) return Optional.empty();
            // Projected Secret volumes use symlinks for atomic rotation.  Resolve
            // them, but reject a malicious link that escapes the configured
            // mount (lexical normalization alone is not sufficient).
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realRoot)) return Optional.empty();
            long size = Files.size(candidate);
            if (size <= 0 || size > MAX_SECRET_BYTES) return Optional.empty();
            String secret = Files.readString(candidate, StandardCharsets.UTF_8);
            return secret.isBlank() ? Optional.empty() : Optional.of(secret);
        } catch (IOException | SecurityException failure) {
            return Optional.empty();
        }
    }

    private static boolean segmentsAreSafe(String[] segments) {
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || segment.length() > 253
                    || !segment.matches("[A-Za-z0-9._-]+") || ".".equals(segment)
                    || "..".equals(segment)) return false;
        }
        return true;
    }
}
