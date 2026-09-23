package com.socp.hips.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.Map;

@Service
@EnableScheduling
public class EndpointForwardingPublisher {
    private final EndpointForwardingStore store;
    private final SocpHttpClient http;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public EndpointForwardingPublisher(EndpointForwardingStore store, SocpHttpClient http,
            ObjectMapper objectMapper, @Value("${socp.hips.forwarding.enabled:true}") boolean enabled) {
        this.store = store; this.http = http; this.objectMapper = objectMapper; this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${socp.hips.forwarding.delay-ms:1000}")
    public void drain() {
        if (!enabled) return;
        // Explicit scope also covers direct invocations, which do not pass through a scheduling proxy.
        try (var ignored = TenantContext.openSystem()) {
            for (int i = 0; i < 5; i++) {
                var claim = store.claim(null, null, Instant.now());
                if (claim.isEmpty()) break;
                deliver(claim.get());
            }
            store.prune(Instant.now().minusSeconds(14 * 86400L));
        }
    }

    public boolean forward(String tenant, String eventId) {
        return TenantContext.callWith(tenant, () -> store.claim(tenant, eventId, Instant.now()).map(this::deliver)
                .orElseGet(() -> "DELIVERED".equals(store.status(tenant, eventId))));
    }

    private boolean deliver(EndpointForwardingStore.Claim claim) {
        return TenantContext.callWith(claim.tenantId(), () -> deliverInTenant(claim));
    }

    private boolean deliverInTenant(EndpointForwardingStore.Claim claim) {
        ServiceCall call;
        try {
            call = http.post(SocpService.SEARCH,
                    "/api/v1/ingest", claim.payload(), SocpHttpClient.NDJSON, 5000,
                    Map.of("Idempotency-Key", "hips:" + claim.eventId()));
        } catch (RuntimeException failure) {
            // Do not persist response bodies or arbitrary exception text that may contain credentials.
            store.fail(claim, Instant.now(), "Transport failure");
            return false;
        }
        if (acknowledged(call)) return store.complete(claim, Instant.now());
        store.fail(claim, Instant.now(), "Search acknowledgement missing; HTTP " + call.status());
        return false;
    }

    /** HTTP success alone does not prove the single submitted event was durably accepted. */
    private boolean acknowledged(ServiceCall call) {
        if (!call.ok() || call.body() == null || call.body().isBlank()) return false;
        try {
            JsonNode response = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.body());
            if (response == null) return false;
            JsonNode data = response.path("data");
            return exactInteger(response.path("code"), 0)
                    && exactInteger(data.path("acknowledged"), 1)
                    && exactInteger(data.path("accepted"), 1)
                    && exactInteger(data.path("skipped"), 0);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private static boolean exactInteger(JsonNode value, int expected) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() == expected;
    }

}
