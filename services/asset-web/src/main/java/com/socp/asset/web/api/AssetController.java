package com.socp.asset.web.api;

import com.socp.asset.web.model.Asset;
import com.socp.asset.web.store.AssetStore;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.socp.platform.auth.RequireRole;
import com.socp.platform.audit.AuditOperation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * ASSET 资产管理 API：CRUD + 采集上报 + 统计。
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetStore store;

    public AssetController(AssetStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Asset> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public Asset create(@Valid @RequestBody CreateAssetRequest req) {
        return store.save(Asset.create(req.name(), req.type(), req.ip(), req.os(), req.owner(), req.criticality()));
    }

    /** 批量导入资产：单条校验失败不会阻断同一批次的其他记录。 */
    @RequireRole({"admin", "analyst"})
    @AuditOperation(action = "IMPORT_ASSET", target = "asset")
    @PostMapping("/import")
    public Map<String, Object> importAssets(@Valid @Size(max = 500) @RequestBody List<@Valid CreateAssetRequest> requests) {
        List<String> errors = new java.util.ArrayList<>();
        int imported = 0;
        for (int index = 0; index < (requests == null ? 0 : requests.size()); index++) {
            CreateAssetRequest req = requests.get(index);
            if (req == null || blank(req.name()) || blank(req.ip())) {
                errors.add("第 " + (index + 1) + " 行缺少名称或 IP");
                continue;
            }
            store.save(Asset.create(req.name().trim(), valueOr(req.type(), "SERVER"), req.ip().trim(),
                    valueOr(req.os(), ""), valueOr(req.owner(), "import"), valueOr(req.criticality(), "HIGH")));
            imported++;
        }
        return Map.of("imported", imported, "skipped", errors.size(), "errors", errors);
    }

    @RequireRole({"admin", "analyst"})
    @PutMapping("/{id}")
    public Asset update(@PathVariable String id, @Valid @RequestBody CreateAssetRequest req) {
        Asset existing = store.get(id);
        if (existing == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资产不存在");
        return store.save(new Asset(id, req.name(), req.type(), req.ip(), req.os(), req.owner(), req.criticality(), existing.createdAt()));
    }

    /** 采集服务（asset-collect）上报新资产——按 name 去重，已存在则更新。 */
    @PostMapping("/collect")
    public Map<String, Object> collect(@Valid @RequestBody AssetCollectionRequest request) {
        String name = valueOr(request.name(), "unknown");
        String type = valueOr(request.type(), "SERVER");
        String ip = valueOr(request.ip(), "");
        String os = valueOr(request.os(), "");
        String owner = valueOr(request.owner(), "collect");
        String criticality = valueOr(request.criticality(), "HIGH");
        Asset saved = store.upsertByIp(Asset.create(name, type, ip, os, owner, criticality));
        return Map.of("accepted", true, "assetId", saved.id(), "total", store.list().size());
    }

    /** 资产统计：按类型/关键性/负责人分布。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Asset> all = store.list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("byType", countBy(all, Asset::type));
        out.put("byCriticality", countBy(all, Asset::criticality));
        out.put("byOwner", countBy(all, Asset::owner));
        return out;
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    private static Map<String, Object> countBy(List<Asset> all, java.util.function.Function<Asset, String> f) {
        return all.stream().collect(Collectors.groupingBy(f, Collectors.counting()))
                .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String valueOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    public record CreateAssetRequest(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]*") String type,
            @Size(max = 64) String ip,
            @Size(max = 128) String os,
            @Size(max = 128) String owner,
            @Size(max = 32) String criticality
    ) {
    }
}
