package com.socp.soar.web.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.api.request.CreatePlaybookRequest;
import com.socp.soar.web.api.request.DryRunRequest;
import com.socp.soar.web.api.request.SaveVersionRequest;
import com.socp.soar.web.api.request.ImportPlaybookRequest;
import com.socp.soar.web.api.request.UpdatePlaybookRequest;
import com.socp.soar.web.domain.DefinitionValidationResult;
import com.socp.soar.web.service.SoarService;
import com.socp.soar.web.service.SoarTemplateService;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

import static com.socp.soar.web.api.controller.SoarHttpSupport.badRequest;
import static com.socp.soar.web.api.controller.SoarHttpSupport.clampSize;
import static com.socp.soar.web.api.controller.SoarHttpSupport.optionalLong;
import static com.socp.soar.web.api.controller.SoarHttpSupport.optionalString;
import static com.socp.soar.web.api.controller.SoarHttpSupport.optionalStringList;
import static com.socp.soar.web.api.controller.SoarHttpSupport.page;
import static com.socp.soar.web.api.controller.SoarHttpSupport.toObjectMap;

/** Playbook definition, version, validation, and template HTTP API. */
@RestController
@RequestMapping("/api")
public class SoarController {
    private final SoarService service;
    private final SoarTemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarController(SoarService service, SoarTemplateService templates) {
        this.service = service;
        this.templates = templates;
    }

    /** Compatibility constructor for isolated controller tests. */
    public SoarController(SoarService service) {
        this(service, null);
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
            @Valid @RequestBody CreatePlaybookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(
                service.createPlaybook(request.name(), request.description(), request.tags())));
    }

    @PostMapping("/playbooks/import")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> importPlaybook(
            @Valid @RequestBody ImportPlaybookRequest request) {
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
                                                         @Valid @RequestBody(required = false)
                                                         UpdatePlaybookRequest request) {
        return updatePlaybookInternal(id, request);
    }

    /** Compatibility overload for callers compiled against the original map-shaped handler. */
    public ApiResult<Map<String, Object>> updatePlaybook(String id, Object legacyBody) {
        if (legacyBody == null || legacyBody instanceof UpdatePlaybookRequest request) {
            return updatePlaybookInternal(id, (UpdatePlaybookRequest) legacyBody);
        }
        if (!(legacyBody instanceof Map<?, ?> map)) {
            throw badRequest("playbook update must be an object");
        }
        Map<String, Object> payload = toObjectMap(map);
        String name = optionalString(payload.get("name"));
        String description = optionalString(payload.get("description"));
        String status = optionalString(payload.get("status"));
        Long rowVersion = optionalLong(payload.get("rowVersion"));
        List<String> tags = optionalStringList(payload.get("tags"));
        return ApiResult.ok(service.updatePlaybook(id, name, description, tags, status, rowVersion));
    }

    private ApiResult<Map<String, Object>> updatePlaybookInternal(String id, UpdatePlaybookRequest request) {
        if (request == null) {
            return ApiResult.ok(service.updatePlaybook(id, null, null, null, null, null));
        }
        return ApiResult.ok(service.updatePlaybook(id, request.name(), request.description(), request.tags(),
                request.status(), request.rowVersion()));
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
    @ApiResponse(responseCode = "200", description = "Version envelope with the current optimistic-concurrency token",
            headers = @Header(name = "ETag", description = "Weak ETag derived from the version rowVersion",
                    schema = @Schema(type = "string", example = "W/\"3\"")))
    public ApiResult<Map<String, Object>> version(@PathVariable String id, @PathVariable int version,
                                                  jakarta.servlet.http.HttpServletResponse response) {
        ApiResult<Map<String, Object>> result = ApiResult.ok(service.getVersion(id, version));
        applyEtag(response, result.data());
        return result;
    }

    /** Convenience overload for direct handler tests; MVC uses the response variant. */
    public ApiResult<Map<String, Object>> version(String id, int version) {
        return version(id, version, null);
    }

    @GetMapping("/playbooks/{id}/versions/{version}/export")
    @RequirePermission("soar:view")
    public ApiResult<Map<String, Object>> exportVersion(@PathVariable String id, @PathVariable int version) {
        return ApiResult.ok(service.exportVersion(id, version));
    }

    @PutMapping("/playbooks/{id}/versions/{version}")
    @RequirePermission("soar:edit")
    @ApiResponse(responseCode = "200", description = "Updated version envelope with the new optimistic-concurrency token",
            headers = @Header(name = "ETag", description = "Weak ETag for the saved version rowVersion",
                    schema = @Schema(type = "string", example = "W/\"4\"")))
    public ApiResult<Map<String, Object>> saveDraft(@PathVariable String id, @PathVariable int version,
                                                    @Valid @RequestBody SaveVersionRequest request,
                                                    @org.springframework.web.bind.annotation.RequestHeader(
                                                            value = "If-Match", required = false) String ifMatch,
                                                    jakarta.servlet.http.HttpServletResponse response) {
        try {
            Long expectedRowVersion = request.rowVersion();
            if (expectedRowVersion == null && ifMatch != null && !ifMatch.isBlank()) {
                expectedRowVersion = parseIfMatch(ifMatch);
            }
            ApiResult<Map<String, Object>> result = ApiResult.ok(service.saveDraft(id, version,
                    request.definition().toString(),
                    request.layout() == null ? "{}" : request.layout().toString(), expectedRowVersion));
            applyEtag(response, result.data());
            return result;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            if (ifMatch != null && !ifMatch.isBlank()
                    && conflict.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                // RFC 7232: a mismatched precondition is 412, not 409
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.PRECONDITION_FAILED, conflict.getReason(), conflict.getCause());
            }
            throw conflict;
        }
    }

    /** Convenience overload for direct handler tests; MVC uses the header variant. */
    public ApiResult<Map<String, Object>> saveDraft(String id, int version, SaveVersionRequest request) {
        return saveDraft(id, version, request, null, null);
    }

    /** Accepts a bare number or an opaque-tag like `"3"` / `W/"3"`. */
    private static Long parseIfMatch(String header) {
        if (header == null) return null;
        String value = header.trim();
        if (value.startsWith("W/\"")) value = value.substring(3, Math.max(3, value.length() - 1));
        else if (value.startsWith("\"")) value = value.substring(1, Math.max(1, value.length() - 1));
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Weak ETag over the version rowVersion so clients can build If-Match. */
    private static void applyEtag(jakarta.servlet.http.HttpServletResponse response, Map<String, Object> data) {
        if (response == null || data == null) return;
        Object rowVersion = data.get("rowVersion");
        if (rowVersion instanceof Number number) {
            response.setHeader("ETag", "W/\"" + number.longValue() + "\"");
        }
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
                                                 @Valid @RequestBody(required = false) DryRunRequest request) {
        return dryRunInternal(id, version, request);
    }

    /** Compatibility overload for the pre-DTO map-shaped dry-run payload. */
    public ApiResult<Map<String, Object>> dryRun(String id, int version, Object legacyBody) {
        if (legacyBody == null) return dryRunInternal(id, version, null);
        if (legacyBody instanceof DryRunRequest request) {
            return dryRunInternal(id, version, request);
        }
        if (!(legacyBody instanceof Map<?, ?> legacyMap)) {
            throw badRequest("dry-run request must be an object");
        }
        Map<String, Object> payload = toObjectMap(legacyMap);
        Object subject = payload.get("subject");
        Object inputs = payload.get("inputs");
        return ApiResult.ok(service.dryRun(id, version,
                subject instanceof Map<?, ?> subjectMap ? toObjectMap(subjectMap) : Map.of(),
                inputs instanceof Map<?, ?> inputMap ? toObjectMap(inputMap) : payload));
    }

    private ApiResult<Map<String, Object>> dryRunInternal(String id, int version, DryRunRequest request) {
        if (request == null) {
            return ApiResult.ok(service.dryRun(id, version, Map.of(), Map.of()));
        }
        return ApiResult.ok(service.dryRun(id, version,
                request.subject() == null ? Map.of() : request.subject(),
                request.inputs() == null ? Map.of() : request.inputs()));
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

    /** Restore an older revision as a new editable draft; published history is never rewritten. */
    @PostMapping("/playbooks/{id}/versions/{version}/rollback")
    @RequirePermission("soar:edit")
    public ResponseEntity<ApiResult<Map<String, Object>>> rollback(@PathVariable String id, @PathVariable int version) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(service.rollbackToDraft(id, version)));
    }

}
