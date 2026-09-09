package com.socp.soar.web.connector;

import com.socp.soar.web.config.SoarSecretProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Minimal Vault HTTP provider for {@code vault://mount/path#field} references.
 * It supports both KV v1 ({@code data.field}) and KV v2
 * ({@code data.data.field}) responses.  Values are fetched on every resolve so
 * Vault rotation/version changes are visible to the next Activity.
 */
@Component
@ConditionalOnProperty(prefix = "socp.soar.secrets", name = "backend", havingValue = "vault")
public class VaultSecretResolver implements SecretResolver {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final SoarSecretProperties properties;
    private final ObjectMapper mapper;
    private final SecretResolver tokenResolver;
    private final HttpClient http;
    private final URI endpoint;

    @org.springframework.beans.factory.annotation.Autowired
    public VaultSecretResolver(SoarSecretProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new EnvironmentSecretResolver());
    }

    VaultSecretResolver(SoarSecretProperties properties, ObjectMapper mapper,
                        SecretResolver tokenResolver) {
        this.properties = properties == null ? new SoarSecretProperties() : properties;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
        this.tokenResolver = tokenResolver == null ? new EnvironmentSecretResolver() : tokenResolver;
        this.endpoint = parseEndpoint(this.properties);
        require(this.properties.getVaultTokenRef(), "SOAR Vault token reference");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeout(this.properties.getConnectTimeoutMs(), 100, 60_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) return Optional.empty();
        String value = reference.trim();
        if (value.startsWith("env://") || value.startsWith("secret://")) {
            return properties.isAllowEnvironmentFallback()
                    ? tokenResolver.resolve(value) : Optional.empty();
        }
        if (!value.startsWith("vault://")) return Optional.empty();
        VaultReference parsed = parseReference(value.substring("vault://".length()));
        if (parsed == null) return Optional.empty();
        Optional<String> token = resolveToken();
        if (token.isEmpty()) return Optional.empty();
        URI requestUri = objectUri(parsed.path());
        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofMillis(timeout(properties.getRequestTimeoutMs(), 100, 120_000)))
                .header("Accept", "application/json")
                .header("X-Vault-Request", "true")
                .header("X-Vault-Token", token.get())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null || response.body().length > MAX_RESPONSE_BYTES) {
                return Optional.empty();
            }
            JsonNode body = mapper.readTree(response.body());
            JsonNode data = body == null ? null : body.get("data");
            if (data == null || !data.isObject()) return Optional.empty();
            JsonNode nested = data.get("data");
            if (nested != null && nested.isObject()) data = nested;
            JsonNode field = data.get(parsed.field());
            if (field == null || field.isNull() || !field.isValueNode()) return Optional.empty();
            String resolved = field.isTextual() ? field.asText() : field.toString();
            return resolved.isBlank() ? Optional.empty() : Optional.of(resolved);
        } catch (IOException failure) {
            return Optional.empty();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private URI objectUri(String path) {
        String base = endpoint.toString().replaceAll("/+$", "");
        StringBuilder encoded = new StringBuilder(base).append("/v1");
        for (String segment : path.split("/", -1)) encoded.append('/').append(encode(segment));
        return URI.create(encoded.toString());
    }

    private Optional<String> resolveToken() {
        String reference = properties.getVaultTokenRef() == null
                ? "" : properties.getVaultTokenRef().trim();
        if (reference.startsWith("k8s://")) {
            return new KubernetesSecretResolver(properties).resolve(reference)
                    .filter(item -> !item.isBlank());
        }
        if (properties.isAllowEnvironmentFallback()
                && (reference.startsWith("env://") || reference.startsWith("secret://"))) {
            return tokenResolver.resolve(reference).filter(item -> !item.isBlank());
        }
        return Optional.empty();
    }

    private static VaultReference parseReference(String value) {
        int marker = value.indexOf('#');
        if (marker <= 0 || marker == value.length() - 1 || value.indexOf('?', marker) >= 0) return null;
        String path = value.substring(0, marker);
        String field = value.substring(marker + 1);
        String[] segments = path.split("/", -1);
        if (segments.length < 2 || !safeField(field)) return null;
        for (String segment : segments) {
            if (!safeField(segment) || ".".equals(segment) || "..".equals(segment)) return null;
        }
        return new VaultReference(path, field);
    }

    private static URI parseEndpoint(SoarSecretProperties properties) {
        String value = require(properties.getVaultEndpoint(), "SOAR Vault endpoint");
        URI uri;
        try { uri = URI.create(value.trim()); }
        catch (IllegalArgumentException failure) { throw new IllegalStateException("SOAR Vault endpoint is invalid"); }
        String path = uri.getPath();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (uri.getHost() == null || (!"https".equals(scheme) && !"http".equals(scheme))
                || uri.getQuery() != null || uri.getFragment() != null
                || uri.getUserInfo() != null || (path != null && path.contains(".."))) {
            throw new IllegalStateException("SOAR Vault endpoint is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !properties.isAllowInsecure()) {
            throw new IllegalStateException("SOAR_VAULT_ENDPOINT must use HTTPS unless allow-insecure is explicit");
        }
        return uri;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " is not configured");
        return value.trim();
    }

    private static boolean safeField(String value) {
        return value != null && value.length() >= 1 && value.length() <= 253
                && value.matches("[A-Za-z0-9._-]+");
    }

    private static String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int code = item & 0xff;
            if ((code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z')
                    || (code >= '0' && code <= '9') || code == '-' || code == '_'
                    || code == '.' || code == '~') out.append((char) code);
            else out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", code));
        }
        return out.toString();
    }

    private static int timeout(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record VaultReference(String path, String field) { }
}
