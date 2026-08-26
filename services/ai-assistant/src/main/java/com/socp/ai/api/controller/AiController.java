package com.socp.ai.api.controller;

import com.socp.ai.api.request.*;
import com.socp.ai.domain.AiResult;
import com.socp.ai.service.AiAssistantService;
import com.socp.ai.service.InvestigationAgentService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

/**
 * AI 助手 API：自然语言安全问答。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiAssistantService service;
    private final InvestigationAgentService investigation;

    public AiController(AiAssistantService service, InvestigationAgentService investigation) {
        this.service = service;
        this.investigation = investigation;
    }

    @PostMapping("/ask")
    public AiResult ask(@Valid @RequestBody AiAskRequest request) {
        return service.ask(request.question());
    }

    /** Evidence-first investigation entry point; all tools are tenant-scoped. */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "AI_INVESTIGATION", target = "alert")
    @PostMapping("/investigations")
    public java.util.Map<String, Object> investigate(@Valid @RequestBody InvestigationRequest request) {
        return investigation.investigate(request.alertId());
    }

    @RequireRole({"admin", "analyst"})
    @GetMapping("/investigations/{id}")
    public java.util.Map<String, Object> getInvestigation(@PathVariable String id) {
        return investigation.get(id);
    }

    /** Explicit analyst action; the generated SOAR suggestions are never executed here. */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "AI_INVESTIGATION_APPEND", target = "incident")
    @PostMapping("/investigations/{id}/append-to-incident")
    public java.util.Map<String, Object> appendToIncident(
            @PathVariable String id,
            @RequestBody(required = false) AppendInvestigationRequest request) {
        return investigation.appendToIncident(id, request == null ? null : request.incidentId());
    }
}
