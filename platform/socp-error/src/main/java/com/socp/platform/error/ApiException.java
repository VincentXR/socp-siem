package com.socp.platform.error;

/** 业务异常：携带错误码，由 GlobalExceptionHandler 转成 ApiResult */
public class ApiException extends RuntimeException {
    private final int code;
    /** 建议客户端等待的秒数，>0 时由 GlobalExceptionHandler 回写 Retry-After 头（限流场景用） */
    private final long retryAfterSeconds;

    public ApiException(int code, String message) {
        this(code, message, 0L);
    }

    public ApiException(int code, String message, long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ApiException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = 0L;
    }

    public int getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 通用工厂：任意错误码 */
    public static ApiException of(int code, String msg) {
        return new ApiException(code, msg);
    }

    public static ApiException badRequest(String msg) {
        return new ApiException(400, msg);
    }

    /** 未认证：缺少/无效令牌 */
    public static ApiException unauthorized(String msg) {
        return new ApiException(401, msg);
    }

    public static ApiException forbidden(String msg) {
        return new ApiException(403, msg);
    }

    public static ApiException notFound(String msg) {
        return new ApiException(404, msg);
    }

    /** 限流触发（socp-ratelimit 使用） */
    public static ApiException tooManyRequests(String msg) {
        return new ApiException(429, msg);
    }

    /** 限流触发，并告知客户端退避秒数（回写 Retry-After） */
    public static ApiException tooManyRequests(String msg, long retryAfterSeconds) {
        return new ApiException(429, msg, retryAfterSeconds);
    }
}
