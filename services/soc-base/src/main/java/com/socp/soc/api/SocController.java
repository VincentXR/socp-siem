package com.socp.soc.api;

import com.socp.soc.model.TenantInfo;
import com.socp.soc.store.TenantStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.RequireRole;

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

    @GetMapping("/tenants")
    public List<TenantInfo> listTenants() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/tenants")
    public TenantInfo createTenant(@RequestBody Map<String, String> body) {
        return store.save(TenantInfo.create(body.get("name"), body.get("code")));
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return Map.of(
                "tenants", store.list().size(),
                "services", List.of("alert-web", "search-config", "detect-web", "soar-web", "report-web", "asset-web", "hips-web", "ai-assistant"),
                "platform", "SOCP v1.0"
        );
    }
}
