package com.socp.platform.tenant.security;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceRequestSignatureTest {

    @Test
    void signatureBindsServiceMethodPathAndTenant() {
        String signature = ServiceRequestSignature.sign("a-long-shared-secret", "alert-web",
                "POST", "/notify-web/api/v1/notify/alert", "tenant-a", "100", "nonce-1");

        assertTrue(ServiceRequestSignature.verify("a-long-shared-secret", signature, "alert-web",
                "POST", "/notify-web/api/v1/notify/alert", "tenant-a", "100", "nonce-1"));
        assertFalse(ServiceRequestSignature.verify("a-long-shared-secret", signature, "alert-web",
                "POST", "/notify-web/api/v1/notify/alert", "tenant-b", "100", "nonce-1"));
    }

    @Test
    void gatewayBindingBindsThePathAndBearerTokenWithoutExposingTheToken() {
        String binding = ServiceRequestSignature.gatewayBinding("/api/v1/alarms", "bearer-token");

        assertTrue(binding.startsWith("/api/v1/alarms\n"));
        assertFalse(binding.contains("bearer-token"));
        assertFalse(binding.equals(ServiceRequestSignature.gatewayBinding(
                "/api/v1/alarms", "different-token")));
        assertTrue(ServiceRequestSignature.gatewayBinding(null, null).startsWith("\n"));
    }
}
