package com.socp.notify.web.api.controller;

import com.socp.notify.web.api.request.ChannelCreateRequest;
import com.socp.notify.web.api.request.NotifyAlarmRequest;
import com.socp.notify.web.domain.Channel;
import com.socp.notify.web.service.NotificationDispatcher;
import com.socp.notify.web.persistence.store.ChannelStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import com.socp.platform.auth.security.RequireRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;

/**
 * 通知与集成 REST API（context-path /notify-web）。
 * 渠道 CRUD + 告警外发入口（由 alert-web 在创建告警时调用）。
 */
@RestController
@RequestMapping("/api/v1")
public class NotifyController {

    private final ChannelStore channels;
    private final NotificationDispatcher dispatcher;
    private final int maxListSize;

    public NotifyController(ChannelStore channels, NotificationDispatcher dispatcher,
                            @Value("${socp.web.list-max-size:500}") int maxListSize) {
        this.channels = channels;
        this.dispatcher = dispatcher;
        this.maxListSize = maxListSize;
    }

    /** 通知渠道列表：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size，默认 500）。 */
    @GetMapping("/channels")
    public ApiResult<PageResponse<Channel>> channels(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Channel> all = channels.list();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels")
    public ApiResult<Channel> create(@Valid @RequestBody ChannelCreateRequest body) {
        Channel ch = Channel.of(
                body.name().trim(), body.type(), body.target().trim(),
                body.enabledOrDefault(), body.description());
        return ApiResult.ok(channels.add(ch));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels/{id}/toggle")
    public ApiResult<Map<String, Object>> toggle(@PathVariable String id) {
        Channel ch = channels.get(id);
        if (ch == null) return ApiResult.ok(Map.of("error", "not_found"));
        Channel updated = new Channel(ch.id(), ch.name(), ch.type(), ch.target(), !ch.enabled(), ch.description());
        channels.add(updated);
        return ApiResult.ok(Map.of("channel", updated));
    }

    @RequireRole({"admin", "analyst"})
    @DeleteMapping("/channels/{id}")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        return ApiResult.ok(Map.of("removed", channels.delete(id), "id", id));
    }

    @RequireRole({"admin", "analyst"})
    @org.springframework.web.bind.annotation.PutMapping("/channels/{id}")
    public ApiResult<Channel> update(@PathVariable String id, @Valid @RequestBody ChannelCreateRequest body) {
        requireChannel(id);
        return ApiResult.ok(channels.add(new Channel(id, body.name().trim(), body.type(), body.target().trim(),
                body.enabledOrDefault(), body.description())));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels/{id}/test")
    public ResponseEntity<ApiResult<Map<String, Object>>> test(@PathVariable String id) {
        Map<String, Object> result = dispatcher.test(requireChannel(id));
        ApiResult<Map<String, Object>> body = ApiResult.ok(result);
        return ResponseEntity.status("failed".equals(result.get("status"))
                ? HttpStatus.BAD_GATEWAY : HttpStatus.OK).body(body);
    }

    private Channel requireChannel(String id) {
        Channel channel = channels.get(id);
        if (channel == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        return channel;
    }

    /** 告警外发入口：接收 alert-web 推送的告警，分发到启用渠道。 */
    @com.socp.platform.auth.security.RequireService
    @PostMapping("/notify/alert")
    public ResponseEntity<ApiResult<Map<String, Object>>> notify(@Valid @RequestBody NotifyAlarmRequest request) {
        Map<String, Object> result = dispatcher.dispatch(request.asMap());
        int failed = result.get("failed") instanceof Number number ? number.intValue() : 0;
        return ResponseEntity.status(failed == 0 ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                .body(ApiResult.ok(result));
    }

    /** 分发日志：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    @GetMapping("/dispatch-log")
    public ApiResult<PageResponse<Map<String, Object>>> log(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "500") int size) {
        requireValidRange(page, size);
        List<Map<String, Object>> all = dispatcher.log();
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return ApiResult.ok(PageResponse.of(all.subList(from, to), all.size(), page, size));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 0 || size > maxListSize) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

}
