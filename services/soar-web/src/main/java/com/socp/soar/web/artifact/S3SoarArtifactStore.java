package com.socp.soar.web.artifact;

import com.socp.soar.web.config.SoarArtifactProperties;
import com.socp.soar.web.connector.SecretResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Small dependency-free S3 Signature V4 store.  It works with AWS S3 and
 * MinIO's path-style S3 endpoint, keeping the SOAR service free of a provider
 * SDK and making the storage boundary easy to contract-test.
 */
@Component
@ConditionalOnProperty(prefix = "socp.soar.artifacts", name = "backend", havingValue = "s3")
public class S3SoarArtifactStore implements SoarArtifactStore {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final SoarArtifactProperties properties;
    private final SecretResolver secrets;
    private final HttpClient http;
    private final URI endpoint;

    public S3SoarArtifactStore(SoarArtifactProperties properties, SecretResolver secrets) {
        this.properties = properties;
        this.secrets = secrets;
        this.endpoint = parseEndpoint(properties);
        if (!properties.isPathStyle()) {
            throw new IllegalStateException("SOAR_ARTIFACT_S3_PATH_STYLE must be true for the configured endpoint");
        }
        require(properties.getBucket(), "SOAR artifact bucket");
        require(properties.getRegion(), "SOAR artifact region");
        require(properties.getAccessKeyRef(), "SOAR artifact access-key reference");
        require(properties.getSecretKeyRef(), "SOAR artifact secret-key reference");
        int connectTimeout = boundedTimeout(properties.getConnectTimeoutMs(), 100, 60_000);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public StoredArtifact put(String tenantId, String runId, String artifactId,
                               String mediaType, byte[] content) {
        byte[] payload = content == null ? new byte[0] : content;
        ensureSize(payload.length);
        String key = key(tenantId, runId, artifactId);
        URI uri = objectUri(key);
        HttpResponse<byte[]> response = send("PUT", uri, mediaType, payload);
        requireSuccess(response, "put");
        return new StoredArtifact(storageRef(key), payload.length, sha256(payload));
    }

    @Override
    public Optional<byte[]> read(String storageRef) {
        String key = keyFromRef(storageRef);
        HttpResponse<InputStream> response;
        try {
            response = sendStream("GET", objectUri(key), null, new byte[0]);
            if (response.statusCode() == 404) {
                response.body().close();
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                requireSuccess(response, "read");
            }
            try (InputStream input = response.body()) {
                return Optional.of(readBounded(input));
            }
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw storeFailure("read", failure);
        }
    }

    @Override
    public void delete(String storageRef) {
        String key = keyFromRef(storageRef);
        HttpResponse<byte[]> response = send("DELETE", objectUri(key), null, new byte[0]);
        if (response.statusCode() != 404) requireSuccess(response, "delete");
    }

    private HttpResponse<byte[]> send(String method, URI uri, String mediaType, byte[] payload) {
        Credentials credentials = credentials();
        Instant now = Instant.now();
        String payloadHash = sha256(payload);
        Signed signed = signed(method, uri, mediaType, payloadHash, now, credentials);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(boundedTimeout(properties.getRequestTimeoutMs(), 100, 120_000)))
                .header("x-amz-date", signed.amzDate())
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", signed.authorization());
        if (mediaType != null && !mediaType.isBlank()) builder.header("Content-Type", mediaType);
        if ("PUT".equals(method)) builder.PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
        else if ("DELETE".equals(method)) builder.DELETE();
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            throw storeFailure(method, failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw storeFailure(method, failure);
        }
    }

    private HttpResponse<InputStream> sendStream(String method, URI uri, String mediaType, byte[] payload)
            throws IOException, InterruptedException {
        Credentials credentials = credentials();
        Instant now = Instant.now();
        String payloadHash = sha256(payload);
        Signed signed = signed(method, uri, mediaType, payloadHash, now, credentials);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(boundedTimeout(properties.getRequestTimeoutMs(), 100, 120_000)))
                .header("x-amz-date", signed.amzDate())
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", signed.authorization());
        if (mediaType != null && !mediaType.isBlank()) builder.header("Content-Type", mediaType);
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private Signed signed(String method, URI uri, String mediaType, String payloadHash,
                          Instant now, Credentials credentials) {
        String amzDate = AMZ_DATE.format(now);
        String date = SHORT_DATE.format(now);
        String scope = date + "/" + properties.getRegion() + "/s3/aws4_request";
        String host = host(uri);
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        if (mediaType != null && !mediaType.isBlank()) headers.put("content-type", mediaType.trim());
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        StringBuilder canonicalHeaders = new StringBuilder();
        headers.forEach((name, value) -> canonicalHeaders.append(name).append(':')
                .append(value.trim().replaceAll("\\s+", " ")).append('\n'));
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + canonicalUri(uri) + "\n\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + credentials.secret()).getBytes(StandardCharsets.UTF_8),
                date), properties.getRegion()), "s3"), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + credentials.access() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        return new Signed(amzDate, authorization);
    }

    private URI objectUri(String key) {
        String base = endpoint.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + encodeSegment(properties.getBucket()) + "/" + encodeKey(key));
    }

    private String storageRef(String key) {
        return "s3://" + properties.getBucket() + "/" + key;
    }

    private String keyFromRef(String storageRef) {
        String prefix = "s3://" + properties.getBucket() + "/";
        if (storageRef == null || !storageRef.startsWith(prefix)) {
            throw new IllegalArgumentException("SOAR_ARTIFACT_STORAGE_REF_INVALID");
        }
        String key = storageRef.substring(prefix.length());
        if (key.isBlank() || key.contains("..") || key.contains("\\") || !key.matches("[A-Za-z0-9._/-]{1,512}")) {
            throw new IllegalArgumentException("SOAR_ARTIFACT_STORAGE_REF_INVALID");
        }
        return key;
    }

    private String key(String tenantId, String runId, String artifactId) {
        return "soar/" + safeSegment(tenantId) + "/" + safeSegment(runId) + "/" + safeSegment(artifactId);
    }

    private static String safeSegment(String value) {
        if (value == null || value.isBlank()) return "unknown";
        // Replacing punctuation with '_' lets two tenants (for example
        // "acme/prod" and "acme_prod") address the same remote object. Keep
        // ordinary identifiers readable, but hash anything that would be
        // normalized or truncated so the storage namespace remains injective.
        if (value.length() <= 128 && value.matches("[A-Za-z0-9._-]+")) return value;
        return "x-" + sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeKey(String key) {
        StringBuilder result = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (result.length() > 0) result.append('/');
            result.append(encodeSegment(segment));
        }
        return result.toString();
    }

    private static String encodeSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte valueByte : bytes) {
            int codePoint = valueByte & 0xff;
            if ((codePoint >= 'a' && codePoint <= 'z') || (codePoint >= 'A' && codePoint <= 'Z')
                    || (codePoint >= '0' && codePoint <= '9') || codePoint == '-' || codePoint == '_'
                    || codePoint == '.' || codePoint == '~') result.append((char) codePoint);
            else result.append('%').append(String.format(Locale.ROOT, "%02X", codePoint));
        }
        return result.toString();
    }

    private static String canonicalUri(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    private static String host(URI uri) {
        int port = uri.getPort();
        return port < 0 ? uri.getHost() : uri.getHost() + ":" + port;
    }

    private Credentials credentials() {
        String access = secrets.resolve(properties.getAccessKeyRef()).filter(value -> !value.isBlank()).orElseThrow(
                () -> new IllegalStateException("SOAR_ARTIFACT_STORE_ACCESS_KEY_UNAVAILABLE"));
        String secret = secrets.resolve(properties.getSecretKeyRef()).filter(value -> !value.isBlank()).orElseThrow(
                () -> new IllegalStateException("SOAR_ARTIFACT_STORE_SECRET_KEY_UNAVAILABLE"));
        return new Credentials(access, secret);
    }

    private static URI parseEndpoint(SoarArtifactProperties properties) {
        String value = require(properties.getEndpoint(), "SOAR artifact endpoint");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException failure) { throw new IllegalStateException("SOAR artifact endpoint is invalid"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        if (uri.getHost() == null || (!"https".equals(scheme) && !"http".equals(scheme))
                || (path != null && path.contains(".."))
                || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalStateException("SOAR artifact endpoint is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !properties.isAllowInsecure()) {
            throw new IllegalStateException("SOAR_ARTIFACT_S3_ENDPOINT must use HTTPS unless allow-insecure is explicit");
        }
        return uri;
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " is not configured");
        return value.trim();
    }

    private static int boundedTimeout(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void ensureSize(int size) {
        if (size > MAX_BYTES) throw new IllegalArgumentException("SOAR_ARTIFACT_TOO_LARGE");
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) throw new IllegalStateException("SOAR_ARTIFACT_TOO_LARGE");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void requireSuccess(HttpResponse<?> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("SOAR_ARTIFACT_STORE_" + operation.toUpperCase(Locale.ROOT)
                    + "_FAILED_HTTP_" + response.statusCode());
        }
    }

    private static RuntimeException storeFailure(String operation, Exception failure) {
        return new IllegalStateException("SOAR_ARTIFACT_STORE_" + operation.toUpperCase(Locale.ROOT) + "_FAILED", failure);
    }

    private static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest(bytes)); }

    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] hmac(byte[] key, String value) { return hmac(key, value.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("SOAR_ARTIFACT_SIGNING_FAILED", failure);
        }
    }

    private record Credentials(String access, String secret) { }
    private record Signed(String amzDate, String authorization) { }
}
