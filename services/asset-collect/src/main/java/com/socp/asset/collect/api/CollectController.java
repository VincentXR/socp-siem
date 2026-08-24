package com.socp.asset.collect.api;

import com.socp.asset.collect.collector.AssetScanner;
import com.socp.asset.collect.store.AssetCollectionStore;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.platform.client.SocpService;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASSET 采集入口——接收外部资产/情报上报（SCP/CMDB 同步）。
 * 生产环境消费 Kafka 或 CMDB API；当前为 HTTP 接收。
 *
 * <p>2026-08-12：从"只存内存"升级为「收 → 归一化 → 转发 SEARCH ingest 主链」：
 * 资产/情报事件与日志事件走同一 canonical 管线（解析/落库/OpenSearch/Kafka 检测），
 * 转发 best-effort，失败打 WARN 不丢本地缓冲（与 AssetScanner 上报模式一致）。
 */
@RestController
@RequestMapping("/api/v1")
public class CollectController {

    private static final Logger log = LoggerFactory.getLogger(CollectController.class);

    private final AssetScanner scanner;
    private final SocpHttpClient http;
    private final AssetCollectionStore store;

    public CollectController(AssetScanner scanner, SocpHttpClient http, AssetCollectionStore store) {
        this.scanner = scanner;
        this.http = http;
        this.store = store;
    }

    @PostMapping("/collect")
    public Map<String, Object> collect(@Valid @RequestBody AssetCollectRequest request) {
        Map<String, Object> asset = request.asMap();
        Map<String, Object> record = new LinkedHashMap<>(asset);
        record.put("event.category", "asset");
        record.put("event.action", "discover");
        Map<String, Object> persisted = store.append(tenant(), record);

        // 真转发：归一化采集事件进 SEARCH 主链（NDJSON 单行契约）
        ServiceCall call = http.post(SocpService.SEARCH, "/api/v1/ingest", toJson(persisted),
                SocpHttpClient.NDJSON, 5000);
        if (!call.ok()) {
            log.warn("采集事件转发 SEARCH 失败 id={} 原因={}", persisted.get("id"), call.failureReason());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("status", call.ok() ? "FORWARDED" : "PERSISTED_FORWARD_PENDING");
        result.put("id", persisted.get("id"));
        result.put("total", store.count(tenant()));
        result.put("forwarded", call.ok());
        return result;
    }

    @GetMapping("/collected")
    public List<Map<String, Object>> list() {
        return store.list(tenant());
    }

    /** 定时扫描模拟器发现的资产（已上报 asset-web）。 */
    @GetMapping("/discovered")
    public List<Map<String, Object>> discovered() {
        return scanner.discovered();
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }

    private static String tenant() {
        String tenant = TenantContext.get();
        return tenant == null || tenant.isBlank() ? "default" : tenant;
    }
}
