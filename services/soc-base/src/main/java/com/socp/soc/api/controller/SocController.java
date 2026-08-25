package com.socp.soc.api.controller;

import com.socp.soc.api.request.*;
import com.socp.platform.auth.security.RequireRole;
import com.socp.soc.domain.TenantInfo;
import com.socp.soc.persistence.store.TenantStore;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SOC 基础 API：租户管理 + 平台概览。
 */
@RestController
@RequestMapping("/api/v1")
public class SocController {

    private final TenantStore store;

    public SocController(TenantStore store) {
        this.store = store;
    }

    /** Tenant directory is readable platform metadata; tenant mutation remains admin-only. */
    @RequireRole({"admin", "analyst", "viewer"})
    @GetMapping("/tenants")
    public List<TenantInfo> listTenants() {
        return store.list();
    }

    @RequireRole("admin")
    @PostMapping("/tenants")
    public TenantInfo createTenant(@Valid @RequestBody TenantCreateRequest request) {
        return store.save(TenantInfo.create(request.name().trim(), request.code()));
    }

    /** The counts and service inventory are readable platform metadata. */
    @RequireRole({"admin", "analyst", "viewer"})
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "tenants", store.list().size(),
                "services", List.of("alert-web", "search-config", "detect-web", "soar-web", "report-web", "asset-web", "hips-web", "ai-assistant"),
                "platform", "SOCP v1.0"
        );
    }
}
