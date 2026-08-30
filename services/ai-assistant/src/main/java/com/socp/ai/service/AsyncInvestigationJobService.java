package com.socp.ai.service;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Asynchronous facade so a browser request never owns the full investigation timeout. */
@Service
public class AsyncInvestigationJobService {
    private final InvestigationAgentService agent;

    public AsyncInvestigationJobService(InvestigationAgentService agent) { this.agent = agent; }

    public Map<String, Object> submit(String alertId) {
        String tenant = TenantContext.require();
        // The investigation receipt uses the same deterministic id, so the
        // returned poll URL is immediately usable and duplicate submissions
        // converge on one durable job.
        String jobId = UUID.nameUUIDFromBytes((tenant + "\u0000investigation\u0000" + alertId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        CompletableFuture.runAsync(() -> TenantContext.runWith(tenant, () -> {
            try { agent.investigate(alertId); }
            catch (RuntimeException ignored) { /* durable receipt exposes FAILED */ }
        }));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", "ACCEPTED");
        response.put("alertId", alertId);
        response.put("poll", "/api/v1/ai/investigations/" + jobId);
        return response;
    }
}
