package com.socp.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.MDC;

import java.time.Instant;

/**
 * 统一响应体。全平台所有 REST 出口都走它；traceId 由 obs 层注入 MDC 后回填，
 * 便于前端拿 traceId 去 Jaeger 下钻（见 architecture.md §0.3 / §3 横切机制）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResult<T>(
        int code,
        String message,
        T data,
        String traceId,
        String timestamp
) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data, MDC.get("traceId"), Instant.now().toString());
    }

    public static ApiResult<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null, MDC.get("traceId"), Instant.now().toString());
    }

    /** 业务失败时仍返回 200，靠 code 区分，方便网关/前端统一拦截 */
    public static <T> ApiResult<T> of(int code, String message, T data) {
        return new ApiResult<>(code, message, data, MDC.get("traceId"), Instant.now().toString());
    }
}
