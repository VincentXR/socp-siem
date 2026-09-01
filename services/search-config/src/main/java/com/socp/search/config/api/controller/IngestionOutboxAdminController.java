package com.socp.search.config.api.controller;

import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.ratelimit.api.RateLimit;
import com.socp.search.config.service.IngestionOutboxAdminService;
import com.socp.search.config.api.request.OutboxDiscardRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/outbox/ingestion")
public class IngestionOutboxAdminController {

    private final IngestionOutboxAdminService service;

    public IngestionOutboxAdminController(IngestionOutboxAdminService service) {
        this.service = service;
    }

    @RequireRole({"admin"})
    @RateLimit(permits = 30, seconds = 60)
    @GetMapping("/dead")
    public ApiResult<List<DeadOutboxRecord>> dead() {
        return ApiResult.ok(service.dead());
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "REQUEUE_DEAD_INGESTION_OUTBOX", target = "ingestion_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/{id}/requeue")
    public ApiResult<OutboxAdminResult> requeue(@PathVariable String id) {
        return ApiResult.ok(service.requeue(id));
    }

    @RequireRole({"admin"})
    @AuditOperation(action = "DISCARD_DEAD_INGESTION_OUTBOX", target = "ingestion_outbox")
    @RateLimit(permits = 5, seconds = 60)
    @PostMapping("/{id}/discard")
    public ApiResult<OutboxAdminResult> discard(
            @PathVariable String id, @Valid @RequestBody OutboxDiscardRequest request) {
        return ApiResult.ok(service.discard(id, request.reason()));
    }
}
