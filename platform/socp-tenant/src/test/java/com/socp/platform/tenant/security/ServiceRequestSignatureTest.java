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
}
