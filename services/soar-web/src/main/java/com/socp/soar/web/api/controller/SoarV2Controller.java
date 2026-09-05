package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.auth.security.RequireService;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.api.request.CreateV2PlaybookRequest;
import com.socp.soar.web.api.request.CreateV2AutomationRuleRequest;
import com.socp.soar.web.api.request.CreateV2ConnectorRequest;
import com.socp.soar.web.api.request.RunV2Request;
import com.socp.soar.web.api.request.SaveV2VersionRequest;
import com.socp.soar.web.api.request.ImportV2PlaybookRequest;
import com.socp.soar.web.domain.v2.DefinitionValidationResult;
import com.socp.soar.web.service.SoarV2Service;
import com.socp.soar.web.service.SoarV2AutomationRuleService;
import com.socp.soar.web.service.SoarV2ConnectorService;
import com.socp.soar.web.service.SoarV2TemplateService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Versioned SOAR 2.0 API. V1 remains a separate compatibility surface. */
@RestController
@RequestMapping("/api/v2")
public class SoarV2Controller {
    private static final ScheduledExecutorService STREAMS = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "soar-v2-sse");
        thread.setDaemon(true);
        return thread;
    });
    private final SoarV2Service service;
    private final SoarV2AutomationRuleService automationRules;
    private final SoarV2ConnectorService connectors;
    private final SoarV2TemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2Controller(SoarV2Service service, SoarV2AutomationRuleService automationRules,
                            SoarV2ConnectorService connectors, SoarV2TemplateService templates) {
        this.service = service;
        this.automationRules = automationRules;
        this.connectors = connectors;
        this.templates = templates;
    }

    /** Compatibility constructor for isolated controller tests. */
    public SoarV2Controller(SoarV2Service service, SoarV2AutomationRuleService automationRules,
                            SoarV2ConnectorService connectors) {
        this(service, automationRules, connectors, null);
    }

    @GetMapping("/playbooks")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> listPlaybooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String risk) {
        Page<Map<String, Object>> result = service.listPlaybooks(
                PageRequest.of(Math.max(0, page), clampSize(size)), status, owner, tag, risk);
        return ApiResult.ok(page(result));
    }

    @GetMapping("/templates")
    @RequirePermission("soar:view")
    public ApiResult<List<Map<String, Object>>> templates() {
        return ApiResult.ok(templates == null ? List.of() : templates.list());
    }

    @PostMapping("/templates/{id}/install")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> installTemplate(@PathVariable String id) {
        if (templates == null) throw new IllegalStateException("template catalog unavailable");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(templates.install(id)));
    }

    @PostMapping("/playbooks")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> createPlaybook(
            @Valid @RequestBody CreateV2PlaybookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(
                service.createPlaybook(request.name(), request.description(), request.tags())));
    }

    @PostMapping("/playbooks/import")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> importPlaybook(
            @Valid @RequestBody ImportV2PlaybookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(service.importDraft(
                request.name(), request.description(), request.tags(), request.definition(), request.layout())));
    }

    @GetMapping("/playbooks/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> getPlaybook(@PathVariable String id) {
        return ApiResult.ok(service.getPlaybook(id));
    }

    /** Update metadata or archive/restore a playbook without touching immutable versions. */
    @PatchMapping("/playbooks/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> updatePlaybook(@PathVariable String id,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String name = optionalString(payload.get("name"));
        String description = optionalString(payload.get("description"));
        String status = optionalString(payload.get("status"));
        Long rowVersion = optionalLong(payload.get("rowVersion"));
        List<String> tags = optionalStringList(payload.get("tags"));
        return ApiResult.ok(service.updatePlaybook(id, name, description, tags, status, rowVersion));
    }

    @GetMapping("/playbooks/{id}/versions")
    @RequirePermission("soar:view")
    public ApiResult<List<Map<String, Object>>> versions(@PathVariable String id) {
        return ApiResult.ok(service.listVersions(id));
    }

    @PostMapping("/playbooks/{id}/versions")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> createVersion(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(service.createVersion(id)));
    }

    /** Design-facing spelling; kept as an alias of the version endpoint so
     * older Workbench builds and import clients can use either contract. */
    @PostMapping("/playbooks/{id}/drafts")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> createDraft(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(service.createVersion(id)));
    }

    @GetMapping("/playbooks/{id}/versions/{version}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> version(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.getVersion(id, version));
    }

    @GetMapping("/playbooks/{id}/versions/{version}/export")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> exportVersion(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.exportVersion(id, version));
    }

    @PutMapping("/playbooks/{id}/versions/{version}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> saveDraft(@PathVariable String id, @PathVariable int version,
                                                    @Valid @RequestBody SaveV2VersionRequest request) {
        JsonNode definition = request.definition();
        return ApiResult.ok(service.saveDraft(id, version, definition.toString(),
                request.layout() == null ? "{}" : request.layout().toString(), request.rowVersion()));
    }

    @PostMapping("/playbooks/{id}/versions/{version}/validate")
    @RequirePermission("soar:edit")
    public ApiResult<DefinitionValidationResult> validate(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.validateVersion(id, version));
    }

    @GetMapping("/definition-schema")
    @RequirePermission("soar:view")
    public ApiResult<JsonNode> definitionSchema() {
        return ApiResult.ok(service.definitionSchema());
    }

    @PostMapping("/playbooks/{id}/versions/{version}/dry-run")
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> dryRun(@PathVariable String id, @PathVariable int version,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        Object subject = payload.get("subject");
        Object inputs = payload.get("inputs");
        return ApiResult.ok(service.dryRun(id, version,
                subject instanceof Map<?, ?> map ? toObjectMap(map) : Map.of(),
                inputs instanceof Map<?, ?> map ? toObjectMap(map) : payload));
    }

    @PostMapping("/playbooks/{id}/versions/{version}/publish")
    @RequirePermission("soar:publish")
    public ApiResult<Map<String, Object>> publish(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.publish(id, version));
    }

    @PostMapping("/playbooks/{id}/versions/{version}/deprecate")
    @RequirePermission("soar:publish")
    public ApiResult<Map<String, Object>> deprecate(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.deprecate(id, version));
    }

    @PostMapping("/runs")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> queueRun(@Valid @RequestBody RunV2Request request) {
        Map<String, Object> run = service.queueManualRun(request.requestId(), request.playbookVersionId(),
                request.subject(), request.inputs());
        boolean duplicate = Boolean.TRUE.equals(run.get("duplicate"));
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.ACCEPTED).body(ApiResult.ok(run));
    }

    @GetMapping("/runs")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> listRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String playbookVersionId,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String requestedBy,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo) {
        return ApiResult.ok(page(service.listRuns(PageRequest.of(Math.max(0, page), clampSize(size)), status,
                playbookVersionId, triggerType, requestedBy, parseInstant(createdFrom, "createdFrom"),
                parseInstant(createdTo, "createdTo"))));
    }

    @GetMapping("/runs/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> getRun(@PathVariable String id) {
        return ApiResult.ok(service.getRun(id));
    }

    @GetMapping("/runs/{id}/nodes")
    @RequirePermission("soar:view")
    public ApiResult<Object> nodes(@PathVariable String id,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(service.listNodes(id,
                    PageRequest.of(Math.max(0, page == null ? 0 : page),
                            clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(service.listNodes(id));
    }

    @GetMapping("/runs/{id}/artifacts")
    @RequirePermission("soar:view")
    public ApiResult<Object> artifacts(@PathVariable String id,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(service.listArtifacts(id,
                    PageRequest.of(Math.max(0, page == null ? 0 : page),
                            clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(service.listArtifacts(id));
    }

    @PostMapping("/runs/{id}/artifacts")
    @RequirePermission("soar:task:complete")
    public ResponseEntity<ApiResult<Map<String, Object>>> uploadArtifact(
            @PathVariable String id,
            @RequestParam(required = false) String nodeRunId,
            @RequestParam(required = false, defaultValue = "application/json") String mediaType,
            @RequestParam(required = false, defaultValue = "INTERNAL") String classification,
            @RequestBody(required = false) JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(service.uploadArtifact(
                id, nodeRunId, mediaType, classification, body)));
    }

    @GetMapping("/artifacts/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> artifact(@PathVariable String id) {
        return ApiResult.ok(service.getArtifact(id));
    }

    @GetMapping(value = "/artifacts/{id}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("soar:view")
    public ResponseEntity<String> artifactContent(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(service.getArtifactContent(id));
    }

    @GetMapping("/node-runs/{id}/attempts")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> attempts(@PathVariable String id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> result = service.listNodeAttempts(id,
                PageRequest.of(Math.max(0, page), clampSize(size)));
        return ApiResult.ok(page(result));
    }

    @GetMapping("/runs/{id}/events")
    @RequirePermission("soar:view")
    public ApiResult<Object> events(@PathVariable String id,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size,
                                    @RequestParam(required = false, defaultValue = "0") long after) {
        if (page != null || size != null || after > 0) {
            return ApiResult.ok(page(service.listEvents(id, Math.max(0, after),
                    PageRequest.of(Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(service.listEvents(id));
    }

    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("soar:view")
    public SseEmitter stream(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // The polling callback runs on a scheduler thread, not the request
        // thread.  Capture the authenticated tenant before scheduling so a
        // stream can never fall back to another tenant (or fail with a
        // missing ThreadLocal context) after the HTTP request returns.
        String streamTenant = TenantContext.require();
        long after = parseSequence(lastEventId);
        SseEmitter emitter = new SseEmitter(30_000L);
        final long[] cursor = {after};
        java.util.concurrent.ScheduledFuture<?> task = STREAMS.scheduleAtFixedRate(() -> {
            TenantContext.runWith(streamTenant, () -> {
                try {
                    Page<Map<String, Object>> page = service.listEvents(id, cursor[0], PageRequest.of(0, 100));
                    for (Map<String, Object> event : page.getContent()) {
                        long sequence = event.get("sequence") instanceof Number n ? n.longValue() : cursor[0] + 1;
                        emitter.send(SseEmitter.event().id(String.valueOf(sequence))
                                .name("run-event").data(event));
                        cursor[0] = Math.max(cursor[0], sequence);
                    }
                    // Keep long-lived operator streams alive while a workflow
                    // is waiting on an approval/manual task. Comments are
                    // ignored by EventSource clients but reset the emitter
                    // timeout and make disconnects observable.
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception failure) {
                    emitter.completeWithError(failure);
                }
            });
        }, 0, 500, TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> task.cancel(false));
        emitter.onTimeout(() -> task.cancel(false));
        emitter.onError(ignore -> task.cancel(false));
        return emitter;
    }

    @PostMapping("/runs/{id}/cancel")
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> cancel(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "operator requested cancellation" : optionalString(body.get("reason"));
        if (reason == null || reason.isBlank()) reason = "operator requested cancellation";
        return ApiResult.ok(service.cancelRun(id, reason));
    }

    @PostMapping("/runs/{id}/retry")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> retry(@PathVariable String id,
                                                                @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "operator requested retry" : optionalString(body.get("reason"));
        if (reason == null || reason.isBlank()) reason = "operator requested retry";
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(service.retryRun(id, reason)));
    }

    @PostMapping("/runs/{id}/rerun")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> rerun(@PathVariable String id,
                                                                @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "operator requested rerun" : optionalString(body.get("reason"));
        if (reason == null || reason.isBlank()) reason = "operator requested rerun";
        boolean confirm = body != null && Boolean.parseBoolean(String.valueOf(body.getOrDefault("confirm", false)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(service.rerun(id, reason, confirm)));
    }

    @PostMapping("/node-runs/{id}/resolve-unknown")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> resolveUnknown(@PathVariable String id,
                                                         @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return ApiResult.ok(service.resolveUnknown(id, optionalString(payload.get("resolution")),
                optionalString(payload.get("evidence")), optionalString(payload.get("reason"))));
    }

    @GetMapping("/manual-tasks")
    @RequirePermission("soar:view")
    public ApiResult<Object> manualTasks(
            @RequestParam(defaultValue = "true") boolean pendingOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(service.listManualTasks(pendingOnly, PageRequest.of(
                    Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(service.listManualTasks(pendingOnly));
    }

    @PostMapping("/manual-tasks/{id}/complete")
    @RequirePermission("soar:task:complete")
    public ApiResult<Map<String, Object>> completeManualTask(@PathVariable String id,
                                                             @RequestBody Map<String, Object> body) {
        return ApiResult.ok(service.completeManualTask(id, body == null ? Map.of() : body));
    }

    @GetMapping("/stats")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(service.stats());
    }

    @GetMapping("/operations/dead-dispatches")
    @RequirePermission("soar:operations")
    public ApiResult<List<Map<String, Object>>> deadDispatches() {
        return ApiResult.ok(service.deadDispatches());
    }

    @PostMapping("/operations/dead-dispatches/{id}/requeue")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> requeueDead(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "operator requeue" : optionalString(body.get("reason"));
        return ApiResult.ok(service.requeueDead(id, reason == null ? "operator requeue" : reason));
    }

    @PostMapping("/operations/dead-dispatches/{id}/discard")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> discardDead(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        return ApiResult.ok(service.discardDead(id, optionalString(payload.get("reason"))));
    }

    @GetMapping("/approvals")
    @RequirePermission("soar:approve")
    public ApiResult<Object> approvals(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(service.listApprovals(
                    PageRequest.of(Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(service.listApprovals());
    }

    @PostMapping("/approvals/{id}/decisions")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> decideApproval(@PathVariable String id,
                                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String decision = String.valueOf(payload.getOrDefault("decision", "")).trim().toUpperCase();
        if (!Set.of("APPROVE", "APPROVED", "REJECT", "REJECTED").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision must be APPROVE or REJECT");
        }
        String reason = optionalString(payload.get("reason"));
        return ApiResult.ok(service.decideApproval(id,
                decision.startsWith("APPRO"), reason));
    }

    @PostMapping("/approvals/{id}/approve")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : optionalString(body.get("reason"));
        return ApiResult.ok(service.decideApproval(id, true, reason));
    }

    @PostMapping("/approvals/{id}/reject")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> reject(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? null : optionalString(body.get("reason"));
        return ApiResult.ok(service.decideApproval(id, false, reason));
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
            @Valid @RequestBody CreateV2AutomationRuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(automationRules.create(
                request.name(), request.triggerType(), request.priority(), request.enabled(),
                request.conditions(), request.actions(), request.suppression())));
    }

    @PutMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> updateAutomationRule(@PathVariable String id,
                                                                @Valid @RequestBody CreateV2AutomationRuleRequest request) {
        return ApiResult.ok(automationRules.update(id, request.name(), request.triggerType(), request.priority(),
                request.enabled(), request.conditions(), request.actions(), request.suppression(), request.rowVersion()));
    }

    @PatchMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> patchAutomationRule(@PathVariable String id,
                                                               @RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.ok(automationRules.patch(id, body));
    }

    @DeleteMapping("/automation-rules/{id}")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> deleteAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.remove(id));
    }

    @PostMapping("/automation-rules/{id}/enable")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> enableAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.setEnabled(id, true));
    }

    @PostMapping("/automation-rules/{id}/disable")
    @RequirePermission("soar:edit")
    public ApiResult<Map<String, Object>> disableAutomationRule(@PathVariable String id) {
        return ApiResult.ok(automationRules.setEnabled(id, false));
    }

    @PostMapping("/automation-rules/evaluate")
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> evaluateAutomationRules(
            @RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.evaluate(event));
    }

    @PostMapping("/automation-rules/test")
    @RequirePermission("soar:edit")
    public ApiResult<List<Map<String, Object>>> testAutomationRules(
            @RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.explain(event));
    }

    /** Service/event-bus compatibility route; callers must still pass the service identity at the gateway. */
    @PostMapping("/events/evaluate")
    @RequireService
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> evaluateEvent(@RequestBody Map<String, Object> event) {
        return ApiResult.ok(automationRules.evaluate(event));
    }

    @GetMapping("/connectors")
    @RequirePermission("soar:view")
    public ApiResult<List<Map<String, Object>>> connectors() {
        return ApiResult.ok(connectors.list());
    }

    @GetMapping("/connectors/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> getConnector(@PathVariable String id) {
        return ApiResult.ok(connectors.get(id));
    }

    @GetMapping("/connections/{id}")
    @RequirePermission("soar:connections:view")
    public ApiResult<Map<String, Object>> getConnection(@PathVariable String id) {
        return ApiResult.ok(connectors.get(id));
    }

    @GetMapping("/actions")
    @RequirePermission("soar:view")
    public ApiResult<List<Map<String, Object>>> actions() {
        return ApiResult.ok(connectors.actions());
    }

    /** Connection is the public name in the V2 design; connectors remains a compatibility alias. */
    @GetMapping("/connections")
    @RequirePermission("soar:connections:view")
    public ApiResult<Object> connections(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(connectors.list(PageRequest.of(
                    Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(connectors.list());
    }

    @PostMapping("/connections")
    @RequirePermission("soar:connections:manage")
    public ResponseEntity<ApiResult<Map<String, Object>>> createConnection(
            @Valid @RequestBody CreateV2ConnectorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(connectors.create(
                request.name(), request.connectorType(), request.endpoint(), request.authSecretRef(),
                request.allowedHosts(), request.enabled())));
    }

    @PutMapping("/connections/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> updateConnection(@PathVariable String id,
                                                            @Valid @RequestBody CreateV2ConnectorRequest request) {
        return ApiResult.ok(connectors.update(id, request.name(), request.connectorType(), request.endpoint(),
                request.authSecretRef(), request.allowedHosts(), request.enabled(), request.rowVersion()));
    }

    @PatchMapping("/connections/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> patchConnection(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body) {
        Map<String, Object> current = connectors.get(id);
        Map<String, Object> payload = body == null ? Map.of() : body;
        String name = optionalString(payload.getOrDefault("name", current.get("name")));
        String type = optionalString(payload.getOrDefault("connectorType", current.get("connectorType")));
        String endpoint = optionalString(payload.getOrDefault("endpoint", current.get("endpoint")));
        String secret = payload.containsKey("authSecretRef") ? optionalString(payload.get("authSecretRef")) : null;
        @SuppressWarnings("unchecked")
        List<String> allowedHosts = payload.get("allowedHosts") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : optionalStringList(current.get("allowedHosts"));
        boolean enabled = payload.containsKey("enabled")
                ? Boolean.parseBoolean(String.valueOf(payload.get("enabled")))
                : Boolean.TRUE.equals(current.get("enabled"));
        return ApiResult.ok(connectors.update(id, name, type, endpoint, secret, allowedHosts, enabled,
                optionalLong(payload.get("rowVersion"))));
    }

    @PostMapping("/connectors")
    @RequirePermission("soar:connections:manage")
    public ResponseEntity<ApiResult<Map<String, Object>>> createConnector(
            @Valid @RequestBody CreateV2ConnectorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(connectors.create(
                request.name(), request.connectorType(), request.endpoint(), request.authSecretRef(),
                request.allowedHosts(), request.enabled())));
    }

    @PutMapping("/connectors/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> updateConnector(@PathVariable String id,
                                                           @Valid @RequestBody CreateV2ConnectorRequest request) {
        return ApiResult.ok(connectors.update(id, request.name(), request.connectorType(), request.endpoint(),
                request.authSecretRef(), request.allowedHosts(), request.enabled(), request.rowVersion()));
    }

    @PostMapping("/connectors/{id}/enable")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> enableConnector(@PathVariable String id) {
        return ApiResult.ok(connectors.setEnabled(id, true));
    }

    @PostMapping("/connectors/{id}/disable")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> disableConnector(@PathVariable String id) {
        return ApiResult.ok(connectors.setEnabled(id, false));
    }

    @PostMapping("/connectors/{id}/test")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> testConnector(@PathVariable String id) {
        return ApiResult.ok(connectors.test(id));
    }

    @PostMapping("/connections/{id}/test")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> testConnection(@PathVariable String id) {
        return ApiResult.ok(connectors.test(id));
    }

    /** Connection spelling of the compatibility enable/disable operations. */
    @PostMapping("/connections/{id}/enable")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> enableConnection(@PathVariable String id) {
        return ApiResult.ok(connectors.setEnabled(id, true));
    }

    @PostMapping("/connections/{id}/disable")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> disableConnection(@PathVariable String id) {
        return ApiResult.ok(connectors.setEnabled(id, false));
    }

    @DeleteMapping("/connections/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> deleteConnection(@PathVariable String id) {
        return ApiResult.ok(connectors.softDelete(id));
    }

    private static Map<String, Object> page(Page<Map<String, Object>> result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("total", result.getTotalElements());
        out.put("items", result.getContent());
        return out;
    }

    private static int clampSize(int size) {
        return Math.min(200, Math.max(1, size));
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Math.max(0, Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static Map<String, Object> toObjectMap(Map<?, ?> value) {
        Map<String, Object> output = new LinkedHashMap<>();
        value.forEach((key, item) -> output.put(String.valueOf(key), item));
        return output;
    }

    private static String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long optionalLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static List<String> optionalStringList(Object value) {
        if (!(value instanceof List<?> list)) return null;
        return list.stream().map(String::valueOf).toList();
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.trim()); }
        catch (DateTimeParseException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be an ISO-8601 instant", failure);
        }
    }
}
