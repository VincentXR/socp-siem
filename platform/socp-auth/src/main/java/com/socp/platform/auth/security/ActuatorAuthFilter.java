package com.socp.platform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * actuator 防线（2026-09 审查修复）。
 *
 * <p>Spring Boot 的 actuator 端点由独立的 HandlerMapping 应答，而 {@link AuthInterceptor}
 * 通过 {@code WebMvcConfigurer#addInterceptors} 注册，只会被注入 MVC 自己创建的映射，
 * 对 {@code /actuator/**} 根本不生效。拦截器覆盖不到的地方必须由 servlet Filter 补齐：
 * Filter 在 DispatcherServlet 之前执行，对所有映射都生效。</p>
 *
 * <p>口径与 {@link AuthInterceptor} 双轨一致（复用其 preHandle；传入非 HandlerMethod 的
 * 哨兵 handler 时只做认证、不做注解授权，正好对应 actuator 端点没有角色注解的既定设计）：</p>
 * <ul>
 *   <li>{@code /actuator/health}（含 {@code /liveness}、{@code /readiness} 组）保持公开，
 *       与 SocpAuthConfig 的拦截器排除列表一致；</li>
 *   <li>其余 {@code /actuator/**} 需要凭据：metrics 凭据（只允许 {@code prometheus}/{@code metrics}
 *       路径）、有效用户/服务 JWT 或 dev-bypass；采集凭据不能读运行时指标（被限定在采集路径）。</li>
 * </ul>
 *
 * <p>prod 下 {@code socp.security.require-gateway=true} 时用户 JWT 读指标仍需网关签名，
 * Prometheus 因此只能使用 metrics 凭据；这条补偿控制（deploy/helm/README.md 的既定口径）
 * 此前因为拦截器不参与 actuator 而是死代码。</p>
 */
@Component
@Order(ActuatorAuthFilter.ORDER)
public class ActuatorAuthFilter extends OncePerRequestFilter implements Ordered {

    /** Runs before the tenant filter (-200) and before any handler mapping lookup. */
    public static final int ORDER = -220;

    private static final Logger log = LoggerFactory.getLogger(ActuatorAuthFilter.class);
    private static final String ACTUATOR = "/actuator";
    private static final String HEALTH = "/actuator/health";

    /**
     * Deliberately not a {@code HandlerMethod}: the reused interceptor performs
     * authentication only, because actuator endpoints carry no role annotation.
     */
    private static final Object ACTUATOR_ENDPOINT = new Object();

    private final AuthInterceptor authInterceptor;
    private final ObjectMapper objectMapper;

    public ActuatorAuthFilter(AuthInterceptor authInterceptor, ObjectMapper objectMapper) {
        this.authInterceptor = authInterceptor;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = relativePath(request);
        if (!isActuatorPath(path) || isPublicHealth(path)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            authInterceptor.preHandle(request, response, ACTUATOR_ENDPOINT);
        } catch (ApiException denied) {
            reject(response, denied.getCode(), denied.getMessage());
            return;
        } catch (RuntimeException failure) {
            log.warn("Actuator authentication failed path={} reason={}", path, failure.toString());
            reject(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), "Actuator authentication is unavailable");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // The identity above is request-scoped and actuator handlers never
            // clear it: a pooled servlet thread must not carry it into the next
            // request.
            AuthenticatedIdentityContext.clear();
            TenantContext.clear();
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    static boolean isActuatorPath(String relativePath) {
        return ACTUATOR.equals(relativePath) || relativePath.startsWith(ACTUATOR + "/");
    }

    static boolean isPublicHealth(String relativePath) {
        return HEALTH.equals(relativePath) || relativePath.startsWith(HEALTH + "/");
    }

    /** Context-path relative path, so {@code /alert-web/actuator/metrics} is matched like {@code /actuator/metrics}. */
    static String relativePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return "";
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            String stripped = path.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return path;
    }

    private void reject(HttpServletResponse response, int code, String message) throws IOException {
        HttpStatus resolved = HttpStatus.resolve(code);
        response.setStatus(resolved != null && resolved.isError()
                ? resolved.value() : HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String readable = message == null || message.isBlank()
                ? "Actuator endpoint requires authentication" : message;
        objectMapper.writeValue(response.getWriter(), ApiResult.fail(code, readable));
    }
}
