package com.socp.hips.web.service;

import com.socp.hips.web.HipsWebApplication;
import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = HipsWebApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:endpoint-forwarding-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "socp.demo-data.enabled=false",
        "socp.hips.forwarding.enabled=true", "socp.hips.forwarding.delay-ms=100"
})
@DirtiesContext
class EndpointForwardingRuntimeTest {
    @Autowired EndpointEventDelivery delivery;
    @Autowired EndpointForwardingStore store;
    @Autowired com.socp.hips.web.persistence.store.EndpointEventStore history;
    @MockitoBean SocpHttpClient http;

    @Test void applicationSchedulerRecoversAnUnacknowledgedEventWithoutRequestThreadHelp() {
        var attempts = new AtomicInteger();
        var payloads = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        when(http.post(eq(SocpService.SEARCH), eq("/api/v1/ingest"), anyString(),
                eq(SocpHttpClient.NDJSON), eq(5000), anyMap())).thenAnswer(call -> {
            assertThat(TenantContext.require()).isEqualTo("runtime-tenant");
            String payload = call.getArgument(2);
            payloads.add(payload);
            String storedId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload).path("eventId").asText();
            assertThat(call.<Map<String, String>>getArgument(5)).containsEntry("Idempotency-Key", "hips:" + storedId);
            boolean success = attempts.incrementAndGet() > 1;
            return new ServiceCall(SocpService.SEARCH, "http://fixture", success, success ? 200 : 503,
                    success ? "{\"code\":0,\"data\":{\"accepted\":1,\"acknowledged\":1,\"skipped\":0}}" : "",
                    null, 1, false, 1);
        });
        var input = Map.<String, Object>of("hostname", "host");
        var event = TenantContext.callWith("runtime-tenant", () -> delivery.accept(input, "collector:runtime", "producer-retry"));
        // The first transaction committed, but the original producer may not have received its response.
        assertThat(TenantContext.callWith("runtime-tenant", () -> delivery.accept(input, "collector:runtime", "producer-retry")))
                .isEqualTo(event);
        String id = (String) event.get("eventId");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(store.status("runtime-tenant", id)).isEqualTo("DELIVERED"));
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(payloads).hasSize(2).containsOnly(payloads.peek());
        assertThat(TenantContext.callWith("runtime-tenant", history::count)).isEqualTo(1);
        assertThat(TenantContext.callWith("runtime-tenant", () -> delivery.accept(input, "collector:runtime", "producer-retry")))
                .isEqualTo(event);
        assertThat(delivery.forward(event)).isTrue();
        assertThat(attempts.get()).isEqualTo(2);
    }
}
