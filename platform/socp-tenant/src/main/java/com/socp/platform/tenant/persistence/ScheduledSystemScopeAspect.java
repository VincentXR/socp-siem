package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Gives scheduled maintenance an explicit cross-tenant scope.
 *
 * <p>Schedulers do not carry an HTTP tenant context.  Without this boundary,
 * PostgreSQL RLS correctly fails closed but every outbox/recovery job would be
 * unable to make progress.  A job that needs a narrower tenant scope can still
 * use {@link TenantContext#runWith(String, Runnable)} inside its method.</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ScheduledSystemScopeAspect {

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object withSystemScope(ProceedingJoinPoint joinPoint) throws Throwable {
        try (TenantContext.Scope ignored = TenantContext.openSystem()) {
            return joinPoint.proceed();
        }
    }
}
