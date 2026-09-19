package com.socp.soar.web.api.controller;

import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireService;
import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.api.request.CreateAutomationRuleRequest;
import com.socp.soar.web.service.SoarAutomationRuleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.socp.soar.web.api.controller.SoarHttpSupport.clampSize;
import static com.socp.soar.web.api.controller.SoarHttpSupport.optionalString;
import static com.socp.soar.web.api.controller.SoarHttpSupport.page;

/** Automation-rule and typed event evaluation HTTP API. */
@RestController
@RequestMapping("/api")
public class SoarAutomationController {

    private final SoarAutomationRuleService automationRules;

    public SoarAutomationController(SoarAutomationRuleService automationRules) {
        this.automationRules = automationRules;
    }

    @GetMapping("/automation-rules")
    @RequirePermission("soar:view")
    public ApiResult<Object> automationRules(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(automationRules.list(PageRequest.of(
                    Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(automationRules.list());
    }

    @GetMapping("/automation-rules/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> getAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.get(id));
    }

    @PostMapping("/automation-rules")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> createAutomationRule(
            @Valid @RequestBody CreateAutomationRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(automationRules.create(
                request.name(), request.triggerType(), request.priority(), request.enabled(),
                request.conditions(), request.actions(), request.suppression())));
    }

    @PutMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> updateAutomationRule(
            @PathVariable String id, @Valid @RequestBody CreateAutomationRuleRequest request) {
        return ApiResult.ok(automationRules.update(id, request.name(), request.triggerType(), request.priority(),
                request.enabled(), request.conditions(), request.actions(), request.suppression(),
                request.rowVersion()));
    }

    @PatchMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> patchAutomationRule(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.ok(automationRules.patch(id, body));
    }

    @DeleteMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> deleteAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.remove(id));
    }

    @PostMapping("/automation-rules/{id}/enable")
    @RequirePermission("soar:publish")
    public ApiResult<Map<String, Object>> enableAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.setEnabled(id, true));
    }

    @PostMapping("/automation-rules/{id}/disable")
    @RequirePermission("soar:publish")
    public ApiResult<Map<String, Object>> disableAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.setEnabled(id, false));
    }

    @PostMapping("/automation-rules/evaluate")
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> evaluateAutomationRules(@RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.evaluate(event));
    }

    @PostMapping("/automation-rules/test")
    @RequirePermission("soar:edit")
    public ApiResult<List<Map<String, Object>>> testAutomationRules(@RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.explain(event));
    }

    /** Service/event-bus entry point; callers must pass a signed service identity. */
    @PostMapping("/events/evaluate")
    @RequireService
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> evaluateEvent(@RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.evaluate(normalizeEventEnvelope(event)));
    }

    private static Map<String, Object> normalizeEventEnvelope(Map<String, Object> event) {
        Map<String, Object> source = event == null ? Map.of() : new LinkedHashMap<>(event);
        if (source.containsKey("schemaVersion") || source.containsKey("eventType")) {
            return source;
        }
        String eventId = optionalString(source.get("eventId"));
        if (eventId == null) {
            eventId = optionalString(source.get("id"));
        }
        if (eventId == null) {
            return source;
        }
        String tenantId = optionalString(source.get("tenantId"));
        if (tenantId == null) {
            tenantId = optionalString(source.get("tenant_id"));
        }
        String occurredAt = optionalString(source.get("occurredAt"));
        if (occurredAt == null) {
            occurredAt = Instant.now().toString();
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", "soar.event");
        envelope.put("eventId", eventId);
        envelope.put("eventType", "alert.created");
        if (tenantId != null) {
            envelope.put("tenantId", tenantId);
        }
        envelope.put("occurredAt", occurredAt);
        envelope.put("producer", "alert-web");
        envelope.put("subject", Map.of("type", "alert", "id", eventId));
        envelope.put("data", source);
        envelope.put("trace", Map.of(
                "correlationId", eventId,
                "causationId", optionalString(source.get("triggerEventId")) == null
                        ? eventId : optionalString(source.get("triggerEventId")),
                "automationDepth", 0));
        return envelope;
    }
}
