package com.socp.ai.api.controller;

import com.socp.ai.api.request.AiAskRequest;
import com.socp.ai.api.request.AppendInvestigationRequest;
import com.socp.ai.api.request.InvestigationRequest;
import com.socp.ai.domain.AiResult;
import com.socp.ai.service.AiAssistantService;
import com.socp.ai.service.InvestigationAgentService;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.auth.security.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/**
 * AI 助手 API：自然语言安全问答。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiAssistantService service;
    private final InvestigationAgentService investigation;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.socp.ai.service.AsyncInvestigationJobService asyncInvestigation;

    public AiController(AiAssistantService service, InvestigationAgentService investigation) {
        this.service = service;
        this.investigation = investigation;
    }

    @RequireRole({"admin", "analyst"})
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

    /** Queue an investigation and return immediately; poll the durable receipt for completion. */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "AI_INVESTIGATION_ASYNC", target = "alert")
    @PostMapping("/investigations/async")
    public java.util.Map<String, Object> investigateAsync(@Valid @RequestBody InvestigationRequest request) {
        if (asyncInvestigation == null) return investigate(request);
        return asyncInvestigation.submit(request.alertId());
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
