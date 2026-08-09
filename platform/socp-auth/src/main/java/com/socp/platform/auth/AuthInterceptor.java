package com.socp.platform.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.error.ApiException;
import com.socp.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 鉴权拦截器（Servlet 侧）：
 *  - 机机（M2M）：Authorization: Bearer &lt;client-credentials token&gt;；
 *  - 人机（H2M）：Keycloak OIDC 签发的 JWT。
 *
 * 校验交给 {@link JwtValidator}（验签 + exp/nbf + issuer），通过后从 claim 取租户写入
 * {@link TenantContext}；claim 缺租户时回退到 X-Client-Id 头（保留原有机机约定）。
 *
 * dev-bypass 模式下只校验 Bearer 非空——无 Docker/Keycloak 的本地切片靠这条继续跑 demo-token。
 * 见 architecture.md §0.3：Keycloak 26（人机）+ Spring Authorization Server（机机）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String CLIENT_HEADER = "X-Client-Id";
    private static final String BEARER = "Bearer ";

    private final JwtValidator jwtValidator;

    public AuthInterceptor(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        String auth = req.getHeader(HttpHeaders.AUTHORIZATION);
        // 缺令牌 = 未认证 → 401（403 语义是"已认证但无权限"），与网关侧保持一致
        if (auth == null || !auth.startsWith(BEARER)) {
            throw ApiException.unauthorized("缺少 Bearer 令牌");
        }
        String token = auth.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            throw ApiException.unauthorized("空令牌");
        }

        String tenantFromClaim = null;
        if (!jwtValidator.isDevBypass()) {
            JWTClaimsSet claims;
            try {
                claims = jwtValidator.validate(token);
            } catch (JwtValidationException e) {
                log.warn("JWT 校验失败 path={} reason={}", req.getRequestURI(), e.getMessage());
                throw ApiException.unauthorized(e.getMessage());
            }
            tenantFromClaim = jwtValidator.extractTenant(claims);
        }

        if (tenantFromClaim != null && !tenantFromClaim.isBlank() && !"default".equals(tenantFromClaim)) {
            // JWT 携带租户时以 claim 为准（生产多租户安全边界）。
            // 例外：登录内置账号（demo/admin）token 固定 tenant=default——租户以请求头 X-Tenant-Id 为准。
            TenantContext.set(tenantFromClaim);
        } else {
            // 回退 1：机机场景从 X-Tenant-Id 头取租户（集成/测试用，如 verify-slice 的 t1/t2）
            String hdrTenant = req.getHeader("X-Tenant-Id");
            if (hdrTenant != null && !hdrTenant.isBlank()) {
                TenantContext.set(hdrTenant);
                return true;
            }
            // 回退 2：机机场景从 X-Client-Id 取租户（形如 tenantA:svc-xxx）
            String client = req.getHeader(CLIENT_HEADER);
            if (client != null && !client.isBlank()) {
                TenantContext.set(client.contains(":") ? client.substring(0, client.indexOf(':')) : client);
            }
        }
        // 角色/权限注入（@PreAuthorize）在此扩展
        return true;
    }
}
