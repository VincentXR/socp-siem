package com.socp.hips.web.api.controller;

import com.socp.hips.web.persistence.store.EndpointForwardingStore;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/endpoints/forwarding")
@RequireRole({"admin"})
public class EndpointForwardingController {
    private final EndpointForwardingStore store;
    public EndpointForwardingController(EndpointForwardingStore store) { this.store = store; }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(defaultValue = "DEAD") String status,
            @RequestParam(defaultValue = "100") int limit) {
        if (!Set.of("PENDING", "PROCESSING", "DELIVERED", "DEAD").contains(status) || limit < 1 || limit > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid forwarding status or limit");
        return ApiResult.ok(store.list(TenantContext.require(), status, limit));
    }

    @PostMapping("/{id}/requeue")
    @com.socp.platform.audit.api.AuditOperation(action = "REQUEUE_ENDPOINT_FORWARDING", target = "endpoint_forwarding")
    @com.socp.platform.ratelimit.api.RateLimit(permits = 5, seconds = 60)
    public ApiResult<Map<String, Object>> requeue(@PathVariable String id) {
        if (!store.requeue(TenantContext.require(), id, Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No owned DEAD forwarding receipt");
        return ApiResult.ok(Map.of("eventId", id, "status", "PENDING"));
    }
}
