package com.socp.platform.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 租户注入过滤器（最高优先级）：从 X-Tenant-Id 头（或 JWT claim）取出租户，写入 TenantContext。
 * 文档要求“多租户靠 SDK 强制”而非业务自觉——这里在入口强制设置，缺失则 400。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            String tenant = req.getHeader(HEADER);
            if (tenant != null && !tenant.isBlank()) {
                if (!TenantContext.isValid(tenant)) {
                    res.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid X-Tenant-Id");
                    return;
                }
                TenantContext.set(tenant);
            }
            // 缺失租户时不在过滤器拦截（部分 actuator/health 无需租户），由 require() 在业务点强制
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
