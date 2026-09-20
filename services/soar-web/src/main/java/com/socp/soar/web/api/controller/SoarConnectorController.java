package com.socp.soar.web.api.controller;

import com.socp.platform.auth.security.RequirePermission;
import com.socp.platform.error.api.ApiResult;
import com.socp.soar.web.api.request.CreateConnectorRequest;
import com.socp.soar.web.api.request.PatchConnectionRequest;
import com.socp.soar.web.service.SoarConnectorService;
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

import java.util.List;
import java.util.Map;

import static com.socp.soar.web.api.controller.SoarHttpSupport.clampSize;
import static com.socp.soar.web.api.controller.SoarHttpSupport.page;

/** Connector catalog and tenant connection management HTTP API. */
@RestController
@RequestMapping("/api")
public class SoarConnectorController {

    private final SoarConnectorService connectors;

    public SoarConnectorController(SoarConnectorService connectors) {
        this.connectors = connectors;
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

    /** Connection is the design name; connectors remains a compatibility alias. */
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
            @Valid @RequestBody CreateConnectorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(connectors.create(
                request.name(), request.connectorType(), request.endpoint(), request.authSecretRef(),
                request.allowedHosts(), request.enabled())));
    }

    @PutMapping("/connections/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> updateConnection(
            @PathVariable String id, @Valid @RequestBody CreateConnectorRequest request) {
        return ApiResult.ok(connectors.update(id, request.name(), request.connectorType(), request.endpoint(),
                request.authSecretRef(), request.allowedHosts(), request.enabled(), request.rowVersion()));
    }

    @PatchMapping("/connections/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> patchConnection(
            @PathVariable String id, @Valid @RequestBody PatchConnectionRequest request) {
        return patchConnectionInternal(id, request);
    }

    private ApiResult<Map<String, Object>> patchConnectionInternal(String id, PatchConnectionRequest request) {
        // Merge against the locked row inside one service transaction; reading a
        // GET snapshot here would reintroduce the lost-update window the docs
        // (soar-design If-Match/rowVersion) promise to close.
        return ApiResult.ok(connectors.updatePatch(id,
                request == null ? null : request.name(),
                request == null ? null : request.connectorType(),
                request == null ? null : request.endpoint(),
                request == null ? null : request.authSecretRef(),
                request == null ? null : request.allowedHosts(),
                request == null ? null : request.enabled(),
                request == null ? null : request.rowVersion()));
    }

    @PostMapping("/connectors")
    @RequirePermission("soar:connections:manage")
    public ResponseEntity<ApiResult<Map<String, Object>>> createConnector(
            @Valid @RequestBody CreateConnectorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.ok(connectors.create(
                request.name(), request.connectorType(), request.endpoint(), request.authSecretRef(),
                request.allowedHosts(), request.enabled())));
    }

    @PutMapping("/connectors/{id}")
    @RequirePermission("soar:connections:manage")
    public ApiResult<Map<String, Object>> updateConnector(
            @PathVariable String id, @Valid @RequestBody CreateConnectorRequest request) {
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
}
