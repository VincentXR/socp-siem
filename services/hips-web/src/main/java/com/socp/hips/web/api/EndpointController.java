package com.socp.hips.web.api;

import com.socp.hips.web.model.Endpoint;
import com.socp.hips.web.store.EndpointStore;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * HIPS 端点管理 API：注册 / 列表 / 心跳 / 事件接收 / 统计。
 */
@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointStore store;
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    public EndpointController(EndpointStore store) {
        this.store = store;
    }

    @GetMapping
    public List<Endpoint> list() {
        return store.list();
    }

    @PostMapping
    public Endpoint register(@RequestBody RegisterRequest req) {
        return store.save(Endpoint.register(req.hostname(), req.ip(), req.os(), req.agentVersion()));
    }

    @PostMapping("/{id}/heartbeat")
    public Endpoint heartbeat(@PathVariable String id) {
        return store.heartbeat(id);
    }

    /** 接收 hips-collect 上报的运行时检测事件（Falco 模拟），暂存 + 刷新对应端点心跳。 */
    @PostMapping("/events")
    public Map<String, Object> ingestEvent(@RequestBody Map<String, Object> event) {
        Map<String, Object> record = new LinkedHashMap<>(event);
        record.put("eventId", UUID.randomUUID().toString());
        record.put("receivedAt", Instant.now().toString());
        events.add(record);
        String hostname = String.valueOf(event.getOrDefault("hostname", ""));
        store.list().stream()
                .filter(e -> e.hostname().equals(hostname))
                .findFirst()
                .ifPresent(e -> store.heartbeat(e.id()));
        return Map.of("accepted", true, "eventId", record.get("eventId"), "total", events.size());
    }

    /** 最近收到的端点事件。 */
    @GetMapping("/events")
    public List<Map<String, Object>> events() {
        return List.copyOf(events);
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
        out.put("events", events.size());
        out.put("eventByType", events.stream().collect(Collectors.groupingBy(
                e -> String.valueOf(e.getOrDefault("type", "UNKNOWN")), Collectors.counting())));
        return out;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    public record RegisterRequest(String hostname, String ip, String os, String agentVersion) {
    }
}
