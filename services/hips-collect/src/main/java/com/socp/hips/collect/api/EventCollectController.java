package com.socp.hips.collect.api;

import com.socp.hips.collect.collector.EndpointSimulator;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HIPS 采集入口——接收 Falco/端点 Agent 上报的运行时检测事件。
 * 生产环境走 WebSocket/gRPC；当前为 HTTP 接收 + 内存暂存。
 */
@RestController
@RequestMapping("/api/v1")
public class EventCollectController {

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final EndpointSimulator simulator;

    public EventCollectController(EndpointSimulator simulator) {
        this.simulator = simulator;
    }

    @PostMapping("/events")
    public Map<String, Object> report(@RequestBody Map<String, Object> event) {
        Map<String, Object> record = new LinkedHashMap<>(event);
        record.put("id", UUID.randomUUID().toString());
        record.put("receivedAt", Instant.now().toString());
        events.add(record);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("total", events.size());
        return result;
    }

    @GetMapping("/events")
    public List<Map<String, Object>> list() {
        return List.copyOf(events);
    }

    /** 定时模拟器生成的端点事件（已上报 hips-web）。 */
    @GetMapping("/simulated")
    public List<Map<String, Object>> simulated() {
        return simulator.events();
    }
}
