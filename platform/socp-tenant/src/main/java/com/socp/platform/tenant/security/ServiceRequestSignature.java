package com.socp.platform.tenant.security;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Shared canonical form for tenant-delegating service-to-service requests. */
public final class ServiceRequestSignature {

    public static final String SERVICE_HEADER = "X-Socp-Service";
    public static final String TIMESTAMP_HEADER = "X-Socp-Service-Timestamp";
    public static final String NONCE_HEADER = "X-Socp-Service-Nonce";
    public static final String SIGNATURE_HEADER = "X-Socp-Service-Signature";
    public static final String GATEWAY_PATH_HEADER = "X-Socp-Gateway-Path";
    public static final String GATEWAY_TIMESTAMP_HEADER = "X-Socp-Gateway-Timestamp";
    public static final String GATEWAY_NONCE_HEADER = "X-Socp-Gateway-Nonce";
    public static final String GATEWAY_SIGNATURE_HEADER = "X-Socp-Gateway-Signature";
    public static final String GATEWAY_SERVICE = "api-gateway";

    private ServiceRequestSignature() {
    }

    public static String sign(String secret, String service, String method, String path,
                              String tenant, String timestamp, String nonce) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("service secret is required");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical(service, method, path, tenant, timestamp, nonce)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("unable to sign service request", failure);
        }
    }

    public static boolean verify(String secret, String signature, String service, String method,
                                 String path, String tenant, String timestamp, String nonce) {
        if (signature == null || signature.isBlank()) return false;
        String expected = sign(secret, service, method, path, tenant, timestamp, nonce);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    /** Bind a gateway proof to both the original route and the exact bearer token. */
    public static String gatewayBinding(String path, String bearerToken) {
        return safe(path) + '\n' + sha256(bearerToken);
    }

    private static String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(safe(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("unable to hash gateway credential", failure);
        }
    }

    private static String canonical(String service, String method, String path,
                                    String tenant, String timestamp, String nonce) {
        return safe(service) + '\n' + safe(method).toUpperCase() + '\n' + safe(path) + '\n'
                + safe(tenant) + '\n' + safe(timestamp) + '\n' + safe(nonce);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
