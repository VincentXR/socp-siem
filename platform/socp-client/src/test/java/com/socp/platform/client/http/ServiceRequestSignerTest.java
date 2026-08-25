package com.socp.platform.client.http;




import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ServiceRequestSignerTest {

    @Test
    void addsCompleteServiceIdentityProof() {
        ServiceRequestSigner signer = new ServiceRequestSigner("alert-web", "a-long-shared-secret");
        URI uri = URI.create("http://localhost:18096/notify-web/api/v1/notify/alert");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);

        signer.sign(builder, "POST", uri, "tenant-a");
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();

        assertEquals("alert-web", request.headers().firstValue(ServiceRequestSignature.SERVICE_HEADER).orElseThrow());
        assertNotNull(request.headers().firstValue(ServiceRequestSignature.TIMESTAMP_HEADER).orElse(null));
        assertNotNull(request.headers().firstValue(ServiceRequestSignature.NONCE_HEADER).orElse(null));
        assertNotNull(request.headers().firstValue(ServiceRequestSignature.SIGNATURE_HEADER).orElse(null));
    }
}
