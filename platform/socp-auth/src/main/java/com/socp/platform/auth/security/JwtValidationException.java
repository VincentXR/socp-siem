package com.socp.platform.auth.security;
/**
 * JWT 校验失败。
 *
 * 刻意不复用 socp-error 的 ApiException：JwtValidator 要同时被 Servlet 侧（AuthInterceptor）
 * 和 WebFlux 侧（api-gateway 的 GlobalFilter）使用，网关模块把 spring-boot-starter-web
 * 整棵子树 exclude 掉了，validator 必须保持零 Web 依赖。由各自的调用方翻译成 401 响应。
 */
public class JwtValidationException extends RuntimeException {

    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
