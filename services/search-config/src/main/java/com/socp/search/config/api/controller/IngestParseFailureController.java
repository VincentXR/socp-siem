package com.socp.search.config.api.controller;

import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.service.IngestParseFailureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Tenant-scoped operator access to durable ingest parse failures. */
@RestController
@SearchRuntimeRole(SearchRuntimeRole.Role.API)
@RequestMapping("/api/v1/ingest/parse-failures")
public class IngestParseFailureController {
    private final IngestParseFailureService failures;

    public IngestParseFailureController(IngestParseFailureService failures) {
        this.failures = failures;
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("ingest:read")
    @GetMapping
    public ApiResult<PageResponse<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw ApiException.badRequest("page must be >= 1 and size must be between 1 and 100");
        }
        var result = failures.page(page, size);
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                page, size, result.getTotalPages()));
    }

    @RequireRole({"admin", "analyst"})
    @RequirePermission("ingest:write")
    @AuditOperation(action = "REPLAY_INGEST_PARSE_FAILURE", target = "ingest_parse_failure")
    @PostMapping("/{id}/replay")
    public ApiResult<Map<String, Object>> replay(@PathVariable String id) {
        return ApiResult.ok(failures.replay(id));
    }
}
