package com.socp.platform.error.web;

import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
 *
 * <p>文案策略（见 api-contract.md「错误信封与文案」）：信封 message 只放运维可读、可行动的
 * 人话句。业务 {@link ApiException} 的领域句与请求校验详情原样保留；未处理异常的原文
 * （驱动/SQL/解析器文本）只进日志，对外固定为 {@link #INTERNAL_ERROR_MESSAGE}，
 * 由运维用信封里的 traceId 关联日志与链路下钻。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 5xx 对外固定语句；细节仅留在日志（logback 模式已带 %X{traceId}）。 */
    static final String INTERNAL_ERROR_MESSAGE =
            "服务处理失败，请稍后重试；如持续出现请联系运维并提供追踪码";

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResult<Void>> handleApi(ApiException e) {
        HttpStatus status = toHttpStatus(e.getCode());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (e.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
        }
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? humanSentence(e.getCode()) : e.getMessage();
        if (status.is5xxServerError()) {
            log.error("业务异常 code={} path_status={}", e.getCode(), status.value(), e);
        }
        return builder.body(ApiResult.fail(e.getCode(), message));
    }

    /** 保留 Controller 显式声明的 4xx/5xx 状态，避免被通用 Exception handler 改成 500。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResult<Void>> handleResponseStatus(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        String message = e.getReason() == null || e.getReason().isBlank()
                ? humanSentence(code) : e.getReason();
        if (e.getStatusCode().is5xxServerError()) {
            log.error("声明式响应异常 code={}", code, e);
        }
        return ResponseEntity.status(e.getStatusCode()).body(ApiResult.fail(code, message));
    }

    /** Return a client error for failed request DTO validation instead of leaking it as a 500. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "; " + right)
                .orElse("请求参数校验未通过");
        return ResponseEntity.badRequest().body(ApiResult.fail(400, message));
    }

    /** 只有落在标准 HTTP 错误区间的 code 才映射为真实状态码，业务码一律 200。 */
    private static HttpStatus toHttpStatus(int code) {
        HttpStatus resolved = HttpStatus.resolve(code);
        return resolved != null && resolved.isError() ? resolved : HttpStatus.OK;
    }

    /** 没有领域文案时的兜底人话句：不暴露内部实现，只说清「发生了什么 + 下一步」。 */
    private static String humanSentence(int code) {
        return switch (code) {
            case 400 -> "请求参数不合法，请修正后重试";
            case 401 -> "登录状态已失效，请重新登录";
            case 403 -> "当前账号无权执行该操作";
            case 404 -> "请求的资源不存在，请确认标识或刷新列表";
            case 409 -> "资源状态冲突，请刷新后重试";
            case 413 -> "请求的数据量过大，请缩小查询范围后重试";
            case 429 -> "请求过于频繁，请稍后重试";
            default -> code >= 500 ? INTERNAL_ERROR_MESSAGE : "请求未成功，请稍后重试或联系运维";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleOther(Exception e, HttpServletRequest req) {
        log.error("未处理异常 path={} type={}", req.getRequestURI(), e.getClass().getName(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(500, INTERNAL_ERROR_MESSAGE));
    }
}
