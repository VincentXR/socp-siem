package com.socp.search.config.api.controller;


import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.api.request.SinkTargetRequest;
import com.socp.search.config.api.request.SinkTargetUpdateRequest;
import com.socp.search.config.api.response.SinkTargetView;
import com.socp.search.config.persistence.store.SinkTargetStore;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.error.api.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;

/**
 * 输出目标 API：CRUD。
 *
 * <p>读响应统一走 {@link SinkTargetView}，采集凭据（authToken）只暴露「是否已配置」，
 * 不回显明文；平台内置回退目标不在租户目录里，避免任何租户列表读到平台地址或被其抢占。
 */
@RestController
@com.socp.search.config.config.SearchRuntimeRole(
        com.socp.search.config.config.SearchRuntimeRole.Role.API)
@RequestMapping("/api/v1/outputs")
public class SinkTargetController {

    private final SinkTargetStore store;

    public SinkTargetController(SinkTargetStore store) {
        this.store = store;
    }

    @GetMapping
    public ApiResult<List<SinkTargetView>> list() {
        return ApiResult.ok(store.list().stream().map(SinkTargetView::of).toList());
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "CREATE_SINK_TARGET", target = "sink_target")
    @PostMapping
    public ApiResult<SinkTargetView> create(@Valid @RequestBody SinkTargetRequest target) {
        target.validateHttpOutput();
        return ApiResult.ok(SinkTargetView.of(store.save(target.toDomain())));
    }

    /** Static validation only: never probes operator-controlled URLs or writes an event. */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/validate")
    public ApiResult<Map<String, Object>> validate(@Valid @RequestBody SinkTargetRequest request) {
        request.validateHttpOutput();
        return ApiResult.ok(Map.of("valid", true, "networkTested", false, "writesEvent", false));
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "DELETE_SINK_TARGET", target = "sink_target")
    @DeleteMapping("/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", store.delete(id)));
    }

    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "UPDATE_SINK_TARGET", target = "sink_target")
    @PutMapping("/{id}")
    public ApiResult<SinkTargetView> update(@PathVariable String id,
                                           @Valid @RequestBody SinkTargetUpdateRequest request) {
        request.target().validateHttpOutput();
        return ApiResult.ok(SinkTargetView.of(store.update(id, request)));
    }
}
