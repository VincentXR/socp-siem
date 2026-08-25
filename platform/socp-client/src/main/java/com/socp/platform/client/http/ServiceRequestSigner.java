package com.socp.platform.client.http;




import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.UUID;

/** Adds a short-lived HMAC proof that binds an internal service to its delegated tenant. */
@Component
public class ServiceRequestSigner {

    private final String serviceName;
    private final String secret;

    public ServiceRequestSigner(@Value("${spring.application.name:socp-service}") String serviceName,
                                @Value("${socp.security.service-secret:}") String secret) {
        this.serviceName = serviceName;
        this.secret = secret;
    }

    public void sign(HttpRequest.Builder request, String method, URI uri, String tenant) {
        if (secret == null || secret.isBlank()) return;
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String path = uri.getRawPath();
        String signature = ServiceRequestSignature.sign(secret, serviceName, method, path,
                tenant, timestamp, nonce);
        request.header(ServiceRequestSignature.SERVICE_HEADER, serviceName)
                .header(ServiceRequestSignature.TIMESTAMP_HEADER, timestamp)
                .header(ServiceRequestSignature.NONCE_HEADER, nonce)
                .header(ServiceRequestSignature.SIGNATURE_HEADER, signature);
    }
}
