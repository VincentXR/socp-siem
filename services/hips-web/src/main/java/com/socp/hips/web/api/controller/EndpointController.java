package com.socp.hips.web.api.controller;

import com.socp.hips.web.api.request.EndpointEventRequest;
import com.socp.hips.web.api.request.RegisterRequest;
import com.socp.hips.web.domain.Endpoint;
import com.socp.hips.web.persistence.store.EndpointStore;
import com.socp.hips.web.persistence.store.EndpointEventStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.socp.platform.auth.security.RequireRole;
import jakarta.validation.Valid;

/**
 * HIPS 端点管理 API：注册 / 列表 / 心跳 / 事件接收 / 统计。
 */
@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointStore store;
    private final EndpointEventStore events;

    public EndpointController(EndpointStore store, EndpointEventStore events) {
        this.store = store;
        this.events = events;
    }

    @GetMapping
    public List<Endpoint> list() {
        return store.list();
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public Endpoint register(@Valid @RequestBody RegisterRequest req) {
        return store.save(Endpoint.register(req.hostname(), req.ip(), req.os(), req.agentVersion()));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/{id}/heartbeat")
    public Endpoint heartbeat(@PathVariable String id) {
        return store.heartbeat(id);
    }

    /** 接收 hips-collect 上报的运行时检测事件（Falco 模拟），暂存 + 刷新对应端点心跳。 */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/events")
    public Map<String, Object> ingestEvent(@Valid @RequestBody EndpointEventRequest request) {
        Map<String, Object> record = events.add(request.asMap());
        return Map.of("accepted", true, "eventId", record.get("eventId"), "total", events.list().size());
    }

    /** 最近收到的端点事件。 */
    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return events.list();
    }

    /** 端点统计：在线数 / 事件数 / 事件类型分布。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Endpoint> all = store.list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("online", all.stream().filter(e -> "ONLINE".equals(e.status())).count());
        out.put("byStatus", Map.of(
                "ONLINE", all.stream().filter(e -> "ONLINE".equals(e.status())).count(),
                "OFFLINE", all.stream().filter(e -> "OFFLINE".equals(e.status())).count()
        ));
        List<Map<String, Object>> tenantEvents = events.list();
        out.put("events", tenantEvents.size());
        out.put("eventByType", tenantEvents.stream().collect(Collectors.groupingBy(
                e -> String.valueOf(e.getOrDefault("type", "UNKNOWN")), Collectors.counting())));
        return out;
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

}
