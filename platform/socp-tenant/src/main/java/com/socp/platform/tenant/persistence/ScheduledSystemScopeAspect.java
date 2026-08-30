package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives reviewed maintenance jobs an explicit cross-tenant scope.
 *
 * <p>The scope is attached to {@link TenantSystemJob}, not to every scheduled
 * method. This makes a database-wide RLS bypass visible in code review. A job
 * should switch back to {@link TenantContext#runWith(String, Runnable)} before
 * processing an individual tenant row.</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduledSystemScopeAspect {

    @Around("@annotation(com.socp.platform.tenant.persistence.TenantSystemJob)")
    public Object withSystemScope(ProceedingJoinPoint joinPoint) throws Throwable {
        try (TenantContext.Scope ignored = TenantContext.openSystem()) {
            return joinPoint.proceed();
        }
    }
}
