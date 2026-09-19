package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.api.request.ApprovalDecisionRequest;
import com.socp.soar.web.api.request.PlaybookExecutionRequest;
import com.socp.soar.web.api.request.ReasonRequest;
import com.socp.soar.web.api.request.RerunRequest;
import com.socp.soar.web.api.request.RunRequest;
import com.socp.soar.web.api.request.UnknownResolutionRequest;
import com.socp.soar.web.config.SoarRuntimeProperties;
import com.socp.soar.web.service.SoarRunQueryService;
import com.socp.soar.web.service.SoarService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.socp.soar.web.api.controller.SoarHttpSupport.badRequest;
import static com.socp.soar.web.api.controller.SoarHttpSupport.clampSize;
import static com.socp.soar.web.api.controller.SoarHttpSupport.optionalString;
import static com.socp.soar.web.api.controller.SoarHttpSupport.page;
import static com.socp.soar.web.api.controller.SoarHttpSupport.parseInstant;
import static com.socp.soar.web.api.controller.SoarHttpSupport.parseSequence;
import static com.socp.soar.web.api.controller.SoarHttpSupport.reasonFromLegacy;
import static com.socp.soar.web.api.controller.SoarHttpSupport.toObjectMap;

/** Run execution, artifact, task, approval, and operational HTTP API. */
@RestController
@RequestMapping("/api")
public class SoarRunController {

    private final SoarService service;
    private final ScheduledExecutorService streams;
    private final SoarRuntimeProperties runtimeProperties;
    private final SoarControllerReadSupport reads;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarRunController(SoarService service,
                             @org.springframework.beans.factory.annotation.Qualifier("soarSseScheduler")
                             ObjectProvider<ScheduledExecutorService> schedulerProvider,
                             SoarRuntimeProperties runtimeProperties) {
        this(service, schedulerProvider.getIfAvailable(SoarRunController::compatibilityScheduler),
                runtimeProperties);
    }

    private SoarRunController(SoarService service, ScheduledExecutorService streams,
                              SoarRuntimeProperties runtimeProperties) {
        this.service = service;
        this.streams = streams;
        this.runtimeProperties = runtimeProperties;
        this.reads = new SoarControllerReadSupport(service);
    }

    /** Compatibility constructor for isolated controller tests. */
    public SoarRunController(SoarService service) {
        this(service, compatibilityScheduler(), new SoarRuntimeProperties());
    }

    /** Prefer the dedicated query service when it is available. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRunQueries(SoarRunQueryService runQueries) {
        this.reads.setRunQueries(runQueries);
    }

    @PostMapping("/runs")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> queueRun(@Valid @RequestBody RunRequest request) {
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
        return ApiResult.ok(page(reads.runs(PageRequest.of(Math.max(0, page), clampSize(size)), status,
                playbookVersionId, triggerType, requestedBy, parseInstant(createdFrom, "createdFrom"),
                parseInstant(createdTo, "createdTo"))));
    }

    @GetMapping("/runs/{id}")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> getRun(@PathVariable String id) {
        return ApiResult.ok(reads.run(id));
    }

    @GetMapping("/runs/{id}/nodes")
    @RequirePermission("soar:view")
    public ApiResult<Object> nodes(@PathVariable String id,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(reads.nodes(id,
                    PageRequest.of(Math.max(0, page == null ? 0 : page),
                            clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(reads.nodes(id));
    }

    @GetMapping("/runs/{id}/artifacts")
    @RequirePermission("soar:view")
    public ApiResult<Object> artifacts(@PathVariable String id,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(reads.artifacts(id,
                    PageRequest.of(Math.max(0, page == null ? 0 : page),
                            clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(reads.artifacts(id));
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
        return ApiResult.ok(reads.artifact(id));
    }

    @GetMapping(value = "/artifacts/{id}/content", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission("soar:view")
    public ResponseEntity<String> artifactContent(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(reads.artifactContent(id));
    }

    @GetMapping("/node-runs/{id}/attempts")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> attempts(@PathVariable String id,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Page<Map<String, Object>> result = reads.nodeAttempts(id,
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
            return ApiResult.ok(page(reads.events(id, Math.max(0, after),
                    PageRequest.of(Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(reads.events(id));
    }

    @GetMapping(value = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission("soar:view")
    public SseEmitter stream(@PathVariable String id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (!runtimeProperties.isSseEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SOAR run-event streaming is disabled");
        }
        // The polling callback runs on a scheduler thread, not the request
        // thread. Capture the authenticated tenant before scheduling.
        String streamTenant = TenantContext.require();
        long after = parseSequence(lastEventId);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs());
        final long[] cursor = {after};
        java.util.concurrent.ScheduledFuture<?> task = streams.scheduleAtFixedRate(() -> {
            TenantContext.runWith(streamTenant, () -> {
                try {
                    Page<Map<String, Object>> page = reads.events(id, cursor[0], PageRequest.of(0, 100));
                    for (Map<String, Object> event : page.getContent()) {
                        long sequence = event.get("sequence") instanceof Number n ? n.longValue() : cursor[0] + 1;
                        emitter.send(SseEmitter.event().id(String.valueOf(sequence))
                                .name("run-event").data(event));
                        cursor[0] = Math.max(cursor[0], sequence);
                    }
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception failure) {
                    emitter.completeWithError(failure);
                }
            });
        }, 0, ssePollIntervalMs(), TimeUnit.MILLISECONDS);
        emitter.onCompletion(() -> task.cancel(false));
        emitter.onTimeout(() -> task.cancel(false));
        emitter.onError(ignore -> task.cancel(false));
        return emitter;
    }

    private long ssePollIntervalMs() {
        return Math.max(100L, Math.min(60_000L, runtimeProperties.getSsePollIntervalMs()));
    }

    private long sseTimeoutMs() {
        return Math.max(1_000L, Math.min(24 * 60 * 60 * 1_000L, runtimeProperties.getSseTimeoutMs()));
    }

    private static ScheduledExecutorService compatibilityScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "soar-sse-test");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @PostMapping("/runs/{id}/cancel")
    @RequirePermission("soar:execute")
    public ApiResult<Map<String, Object>> cancel(@PathVariable String id,
                                                 @Valid @RequestBody(required = false) ReasonRequest request) {
        return cancelInternal(id, request == null ? null : request.reason());
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ApiResult<Map<String, Object>> cancel(String id, Object legacyBody) {
        return cancelInternal(id, reasonFromLegacy(legacyBody, "operator requested cancellation"));
    }

    private ApiResult<Map<String, Object>> cancelInternal(String id, String requestedReason) {
        String reason = requestedReason;
        if (reason == null || reason.isBlank()) {
            reason = "operator requested cancellation";
        }
        return ApiResult.ok(service.cancelRun(id, reason));
    }

    @PostMapping("/runs/{id}/retry")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> retry(
            @PathVariable String id, @Valid @RequestBody(required = false) ReasonRequest request) {
        return retryInternal(id, request == null ? null : request.reason());
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ResponseEntity<ApiResult<Map<String, Object>>> retry(String id, Object legacyBody) {
        return retryInternal(id, reasonFromLegacy(legacyBody, "operator requested retry"));
    }

    private ResponseEntity<ApiResult<Map<String, Object>>> retryInternal(String id, String requestedReason) {
        String reason = requestedReason;
        if (reason == null || reason.isBlank()) {
            reason = "operator requested retry";
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(service.retryRun(id, reason)));
    }

    @PostMapping("/runs/{id}/rerun")
    @RequirePermission("soar:execute")
    public ResponseEntity<ApiResult<Map<String, Object>>> rerun(
            @PathVariable String id, @Valid @RequestBody(required = false) RerunRequest request) {
        return rerunInternal(id, request == null ? null : request.reason(),
                request != null && Boolean.TRUE.equals(request.confirm()));
    }

    /** Compatibility overload for the original {reason, confirm} payload. */
    public ResponseEntity<ApiResult<Map<String, Object>>> rerun(String id, Object legacyBody) {
        if (legacyBody == null) {
            return rerunInternal(id, null, false);
        }
        if (legacyBody instanceof RerunRequest request) {
            return rerunInternal(id, request.reason(), Boolean.TRUE.equals(request.confirm()));
        }
        if (!(legacyBody instanceof Map<?, ?> legacyMap)) {
            throw badRequest("rerun request must be an object");
        }
        Map<String, Object> payload = toObjectMap(legacyMap);
        return rerunInternal(id, optionalString(payload.get("reason")),
                Boolean.parseBoolean(String.valueOf(payload.getOrDefault("confirm", false))));
    }

    private ResponseEntity<ApiResult<Map<String, Object>>> rerunInternal(String id, String requestedReason,
                                                                          boolean confirm) {
        String reason = requestedReason;
        if (reason == null || reason.isBlank()) {
            reason = "operator requested rerun";
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(service.rerun(id, reason, confirm)));
    }

    @PostMapping("/node-runs/{id}/resolve-unknown")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> resolveUnknown(@PathVariable String id,
                                                         @Valid @RequestBody UnknownResolutionRequest request) {
        return ApiResult.ok(service.resolveUnknown(id, request.resolution(), request.evidence(), request.reason()));
    }

    /** Compatibility overload for map-shaped resolution requests. */
    public ApiResult<Map<String, Object>> resolveUnknown(String id, Object legacyBody) {
        if (legacyBody instanceof UnknownResolutionRequest request) {
            return ApiResult.ok(service.resolveUnknown(id, request.resolution(), request.evidence(), request.reason()));
        }
        if (!(legacyBody instanceof Map<?, ?> map)) {
            throw badRequest("unknown resolution request must be an object");
        }
        Map<String, Object> payload = toObjectMap(map);
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
            return ApiResult.ok(page(reads.manualTasks(pendingOnly, PageRequest.of(
                    Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(reads.manualTasks(pendingOnly));
    }

    @PostMapping("/manual-tasks/{id}/complete")
    @RequirePermission("soar:task:complete")
    public ApiResult<Map<String, Object>> completeManualTask(@PathVariable String id,
                                                             @RequestBody PlaybookExecutionRequest request) {
        return ApiResult.ok(service.completeManualTask(id, request == null ? Map.of() : request.context()));
    }

    /** Compatibility overload for callers that already hold a decoded map. */
    public ApiResult<Map<String, Object>> completeManualTask(String id, Object legacyBody) {
        if (legacyBody == null) {
            return ApiResult.ok(service.completeManualTask(id, Map.of()));
        }
        if (!(legacyBody instanceof Map<?, ?> map)) {
            throw badRequest("manual task completion must be an object");
        }
        return ApiResult.ok(service.completeManualTask(id, toObjectMap(map)));
    }

    @GetMapping("/stats")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(reads.stats());
    }

    @GetMapping("/operations/dead-dispatches")
    @RequirePermission("soar:operations")
    public ApiResult<List<Map<String, Object>>> deadDispatches() {
        return ApiResult.ok(reads.deadDispatches());
    }

    @PostMapping("/operations/dead-dispatches/{id}/requeue")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> requeueDead(@PathVariable String id,
                                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return requeueDeadInternal(id, request == null ? null : request.reason());
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ApiResult<Map<String, Object>> requeueDead(String id, Object legacyBody) {
        return requeueDeadInternal(id, reasonFromLegacy(legacyBody, "operator requeue"));
    }

    private ApiResult<Map<String, Object>> requeueDeadInternal(String id, String requestedReason) {
        return ApiResult.ok(service.requeueDead(id, requestedReason == null ? "operator requeue" : requestedReason));
    }

    @PostMapping("/operations/dead-dispatches/{id}/discard")
    @RequirePermission("soar:operations")
    public ApiResult<Map<String, Object>> discardDead(@PathVariable String id,
                                                      @Valid @RequestBody(required = false) ReasonRequest request) {
        return ApiResult.ok(service.discardDead(id, request == null ? null : request.reason()));
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ApiResult<Map<String, Object>> discardDead(String id, Object legacyBody) {
        if (legacyBody == null) {
            return ApiResult.ok(service.discardDead(id, null));
        }
        if (legacyBody instanceof ReasonRequest request) {
            return ApiResult.ok(service.discardDead(id, request.reason()));
        }
        if (!(legacyBody instanceof Map<?, ?> map)) {
            throw badRequest("dead-dispatch discard request must be an object");
        }
        return ApiResult.ok(service.discardDead(id, optionalString(toObjectMap(map).get("reason"))));
    }

    @GetMapping("/approvals")
    // Reading the approval queue is part of the SOAR view contract. The
    // approve permission is reserved for decision commands below.
    @RequirePermission("soar:view")
    public ApiResult<Object> approvals(@RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        if (page != null || size != null) {
            return ApiResult.ok(page(reads.approvals(
                    PageRequest.of(Math.max(0, page == null ? 0 : page), clampSize(size == null ? 100 : size)))));
        }
        return ApiResult.ok(reads.approvals());
    }

    @PostMapping("/approvals/{id}/decisions")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> decideApproval(@PathVariable String id,
                                                         @Valid @RequestBody ApprovalDecisionRequest request) {
        return decideApprovalInternal(id, request == null ? null : request.decision(),
                request == null ? null : request.reason());
    }

    /** Compatibility overload for map-shaped approval decisions. */
    public ApiResult<Map<String, Object>> decideApproval(String id, Object legacyBody) {
        if (legacyBody instanceof ApprovalDecisionRequest request) {
            return decideApprovalInternal(id, request.decision(), request.reason());
        }
        if (!(legacyBody instanceof Map<?, ?> map)) {
            throw badRequest("approval decision must be an object");
        }
        Map<String, Object> payload = toObjectMap(map);
        String decision = String.valueOf(payload.getOrDefault("decision", "")).trim().toUpperCase();
        return decideApprovalInternal(id, decision, optionalString(payload.get("reason")));
    }

    private ApiResult<Map<String, Object>> decideApprovalInternal(String id, String rawDecision, String reason) {
        String decision = rawDecision == null ? "" : rawDecision.trim().toUpperCase();
        if (!Set.of("APPROVE", "APPROVED", "REJECT", "REJECTED").contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision must be APPROVE or REJECT");
        }
        return ApiResult.ok(service.decideApproval(id, decision.startsWith("APPRO"), reason));
    }

    @PostMapping("/approvals/{id}/approve")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> approve(@PathVariable String id,
                                                  @Valid @RequestBody(required = false) ReasonRequest request) {
        return ApiResult.ok(service.decideApproval(id, true, request == null ? null : request.reason()));
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ApiResult<Map<String, Object>> approve(String id, Object legacyBody) {
        return ApiResult.ok(service.decideApproval(id, true, reasonFromLegacy(legacyBody, null)));
    }

    @PostMapping("/approvals/{id}/reject")
    @RequirePermission("soar:approve")
    public ApiResult<Map<String, Object>> reject(@PathVariable String id,
                                                 @Valid @RequestBody(required = false) ReasonRequest request) {
        return ApiResult.ok(service.decideApproval(id, false, request == null ? null : request.reason()));
    }

    /** Compatibility overload for the original {reason: ...} payload. */
    public ApiResult<Map<String, Object>> reject(String id, Object legacyBody) {
        return ApiResult.ok(service.decideApproval(id, false, reasonFromLegacy(legacyBody, null)));
    }
}
