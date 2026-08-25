package com.socp.hips.collect.api.controller;

import com.socp.hips.collect.api.request.*;
import com.socp.hips.collect.collector.EndpointSimulator;
import com.socp.hips.collect.persistence.store.HipsEventStore;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HIPS 采集入口——接收 Falco/端点 Agent 上报的运行时检测事件。
 * 生产环境走 WebSocket/gRPC；当前为 HTTP 接收。
 *
 * <p>2026-08-12：从"只存内存"升级为「收 → 转发 SEARCH ingest 主链」：
 * Falco 风格事件（rule/priority/output/fields）由 SEARCH 的 FalcoParser 解析进
 * canonical 管线（落库/OpenSearch/Kafka 检测），转发 best-effort 失败打 WARN。
 */
@RestController
@RequestMapping("/api/v1")
public class EventCollectController {

    private static final Logger log = LoggerFactory.getLogger(EventCollectController.class);

    private final EndpointSimulator simulator;
    private final SocpHttpClient http;
    private final HipsEventStore eventStore;

    public EventCollectController(EndpointSimulator simulator, SocpHttpClient http, HipsEventStore eventStore) {
        this.simulator = simulator;
        this.http = http;
        this.eventStore = eventStore;
    }

    @PostMapping("/events")
    public Map<String, Object> report(@Valid @RequestBody EventCollectRequest request) {
        Map<String, Object> event = request.asMap();
        Map<String, Object> record = eventStore.append(tenant(), event);

        // 真转发：Falco 事件进 SEARCH 主链（FalcoParser 识别 rule/output/fields）
        ServiceCall call = http.post(SocpService.SEARCH, "/api/v1/ingest", toJson(record),
                SocpHttpClient.NDJSON, 5000);
        if (!call.ok()) {
            log.warn("端点事件转发 SEARCH 失败 id={} 原因={}", record.get("id"), call.failureReason());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("total", eventStore.list(tenant()).size());
        result.put("forwarded", call.ok());
        return result;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> list() {
        return eventStore.list(tenant());
    }

    /** 定时模拟器生成的端点事件（已上报 hips-web）。 */
    @GetMapping("/simulated")
    public List<Map<String, Object>> simulated() {
        return simulator.events();
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
