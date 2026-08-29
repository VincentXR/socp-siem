package com.socp.soc.api.controller;

import com.socp.platform.auth.security.RequireRole;
import com.socp.soc.service.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Audit read API; persistence and fallback policy live in the application service. */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/records")
    @RequireRole({"admin", "analyst"})
    public Map<String, Object> records(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action) {
        return queryService.records(limit, action);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return queryService.stats();
    }
}
