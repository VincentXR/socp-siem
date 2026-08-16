package com.socp.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器：把一切异常归一为 ApiResult，避免把堆栈直接吐给前端。
 * 业务异常取 code；其余按 500 处理。traceId 已在 ApiResult 中自动回填 MDC。
 *
 * <p>状态码策略（见 architecture.md §3 横切机制）：
 * <ul>
 *   <li>code 命中标准 HTTP 错误语义（400/401/403/404/429/5xx）→ HTTP 状态码与之对齐，
 *       让网关限流统计、Prometheus 告警、客户端自动退避都能正常工作；</li>
 *   <li>业务自定义码（如 10001 库存不足）→ HTTP 200，靠 body.code 区分，保持统一响应体约定。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<Void>> handleApi(ApiException e) {
        HttpStatus status = toHttpStatus(e.getCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (e.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        return builder.body(ApiResult.fail(e.getCode(), e.getMessage()));
    }

    /** 保留 Controller 显式声明的 4xx/5xx 状态，避免被通用 Exception handler 改成 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String message = e.getReason() == null ? e.getMessage() : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(ApiResult.fail(code, message));
    }

    /** 只有落在标准 HTTP 错误区间的 code 才映射为真实状态码，业务码一律 200。 */
    private static HttpStatus toHttpStatus(int code) {
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved != null && resolved.isError() ? resolved : HttpStatus.OK;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e, HttpServletRequest req) {
        log.error("未处理异常 path={}", req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(500, e.getMessage() == null ? "internal error" : e.getMessage()));
    }
}
