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
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;

/**
 * HIPS 端点管理 API：注册 / 列表 / 心跳 / 事件接收 / 统计。
 */
@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointStore store;
    private final EndpointEventStore events;
    private final int maxListSize;

    public EndpointController(EndpointStore store, EndpointEventStore events,
                              @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.store = store;
        this.events = events;
        this.maxListSize = maxListSize;
    }

    /** 端点列表：租户级分页，page 从 1 起，size 上限 socp.web.list-max-size（默认 500）。 */
    @RequireRole({"admin", "analyst"})
    @GetMapping
    public ApiResult<PageResponse<Endpoint>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Endpoint> all = store.list();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping
    public ApiResult<Endpoint> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResult.ok(store.save(Endpoint.register(req.hostname(), req.ip(), req.os(), req.agentVersion())));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/{id}/heartbeat")
    public ApiResult<Endpoint> heartbeat(@PathVariable String id) {
        return ApiResult.ok(store.heartbeat(id));
    }

    /** 接收 Agent/Falco 上报的运行时检测事件，暂存并刷新对应端点心跳。 */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/events")
    public ApiResult<Map<String, Object>> ingestEvent(@Valid @RequestBody EndpointEventRequest request) {
        Map<String, Object> record = events.add(request.asMap());
        return ApiResult.ok(Map.of("accepted", true, "eventId", record.get("eventId"), "total", events.list().size()));
    }

    /** 最近收到的端点事件：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping("/events")
    public ApiResult<PageResponse<Map<String, Object>>> events(@RequestParam(defaultValue = "1") int page,
                                                               @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Map<String, Object>> all = events.list();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    /** 端点统计：在线数 / 事件数 / 事件类型分布。 */
    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
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
        return ApiResult.ok(out);
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", store.delete(id)));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 0 || size > maxListSize) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

}
