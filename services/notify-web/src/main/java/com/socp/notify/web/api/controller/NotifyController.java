package com.socp.notify.web.api.controller;

import com.socp.notify.web.api.request.ChannelCreateRequest;
import com.socp.notify.web.api.request.NotifyAlarmRequest;
import com.socp.notify.web.api.request.NotificationRecoveryRequest;
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
import com.socp.platform.error.exception.ApiException;
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
        if (size == 0) {
            if (page < 1) throw ApiException.badRequest("分页参数非法：page 从 1 起，size 上限 " + maxListSize);
            return ApiResult.ok(PageResponse.of(List.of(), channels.count(), page, size));
        }
        requireValidRange(page, size);
        var result = channels.list(page, size);
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(), page, size));
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
        Channel updated = channels.toggle(id);
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
        return ApiResult.ok(channels.update(new Channel(id, body.name().trim(), body.type(), body.target().trim(),
                body.enabledOrDefault(), body.description())));
    }

    @RequireRole({"admin", "analyst"})
    @PostMapping("/channels/{id}/test")
    public ResponseEntity<ApiResult<Map<String, Object>>> test(@PathVariable String id) {
        Map<String, Object> result = dispatcher.test(requireChannel(id));
        boolean failed = "failed".equals(result.get("status"));
        // HTTP status and envelope code stay in sync: a failed test is never a
        // code=0 envelope, otherwise clients that only read the code see success.
        return ResponseEntity.status(failed ? HttpStatus.BAD_GATEWAY : HttpStatus.OK)
                .body(failed ? ApiResult.of(502, "通知渠道测试未通过，请核对渠道目标地址与凭据后重试", result)
                        : ApiResult.ok(result));
    }

    private Channel requireChannel(String id) {
        Channel channel = channels.get(id);
        if (channel == null) throw ApiException.notFound("未找到通知渠道 " + id);
        return channel;
    }

    /** 告警外发入口：接收 alert-web 推送的告警，分发到启用渠道。 */
    @com.socp.platform.auth.security.RequireService
    @PostMapping("/notify/alert")
    public ResponseEntity<ApiResult<Map<String, Object>>> notify(@Valid @RequestBody NotifyAlarmRequest request) {
        Map<String, Object> result = dispatcher.dispatch(request.asMap());
        int failed = result.get("failed") instanceof Number number ? number.intValue() : 0;
        boolean retryable = hasRetryableFailure(result);
        HttpStatus status = failed == 0 ? HttpStatus.OK
                : retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        ApiResult<Map<String, Object>> body = failed == 0
                ? ApiResult.ok(result)
                : ApiResult.of(status.value(), retryable
                        ? "部分通知渠道暂时不可用，可安全重试"
                        : "部分通知渠道永久失败或回执未知，请检查明细后人工处理", result);
        return ResponseEntity.status(status).body(body);
    }

    /** SOAR/native service entry point that targets one channel instead of tenant-wide fan-out. */
    @com.socp.platform.auth.security.RequireService
    @PostMapping("/notify/channels/{id}/alert")
    public ResponseEntity<ApiResult<Map<String, Object>>> notifyChannel(
            @PathVariable String id, @Valid @RequestBody NotifyAlarmRequest request) {
        Map<String, Object> result = dispatcher.dispatchToChannel(id, request.asMap());
        int failed = result.get("failed") instanceof Number number ? number.intValue() : 0;
        boolean retryable = hasRetryableFailure(result);
        HttpStatus status = failed == 0 ? HttpStatus.OK
                : retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        ApiResult<Map<String, Object>> body = failed == 0
                ? ApiResult.ok(result)
                : ApiResult.of(status.value(), retryable
                        ? "通知渠道暂时不可用，可安全重试"
                        : "通知渠道永久失败或回执未知，请人工核对", result);
        return ResponseEntity.status(status).body(body);
    }

    @com.socp.platform.auth.security.RequireService
    @PostMapping("/operations/notification-deliveries/requeue")
    public ApiResult<Map<String, Object>> recover(
            @Valid @RequestBody NotificationRecoveryRequest request) {
        return ApiResult.ok(dispatcher.recover(
                request.alarmId(), request.reason(), request.confirmUnknown()));
    }

    @GetMapping("/channels/{id}")
    public ApiResult<Channel> channel(@PathVariable String id) {
        return ApiResult.ok(requireChannel(id));
    }

    /** 分发日志：租户级分页（page 从 1 起，size 上限 socp.web.list-max-size）。 */
    public ApiResult<PageResponse<Map<String, Object>>> log(int page, int size) {
        return log(page, size, null);
    }

    @GetMapping("/dispatch-log")
    public ApiResult<PageResponse<Map<String, Object>>> log(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "500") int size,
                                                            @RequestParam(required = false) String status) {
        requireValidRange(page, size);
        var pageable = org.springframework.data.domain.PageRequest.of(page - 1, size);
        var result = status == null || status.isBlank() ? dispatcher.log(pageable) : dispatcher.log(pageable, status);
        return ApiResult.ok(PageResponse.of(result.getContent(), result.getTotalElements(),
                page, size, result.getTotalPages()));
    }

    private void requireValidRange(int page, int size) {
        if (page < 1 || size < 1 || size > maxListSize || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw ApiException.badRequest("分页参数非法：page 从 1 起，size 上限 " + maxListSize);
        }
    }

    private static boolean hasRetryableFailure(Map<String, Object> dispatch) {
        Object values = dispatch.get("results");
        if (!(values instanceof List<?> results)) return false;
        return results.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .anyMatch(result -> Boolean.TRUE.equals(result.get("retryable")));
    }

}
