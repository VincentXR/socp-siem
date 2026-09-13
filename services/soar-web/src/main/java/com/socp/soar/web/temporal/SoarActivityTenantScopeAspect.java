package com.socp.soar.web.temporal;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.temporal.request.SoarNodeRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Installs the Activity tenant before Spring opens its JPA transaction.
 *
 * <p>Temporal invokes Activities on worker threads, outside the HTTP tenant
 * filter.  The Activity methods already carry the tenant in their durable
 * request, but installing it inside the method is too late: the transaction
 * interceptor may have checked out a connection first.  This advisor runs at
 * the highest precedence so PostgreSQL's RLS scope is present when Hibernate
 * obtains the transaction connection.</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class SoarActivityTenantScopeAspect {

    @Around("execution(* com.socp.soar.web.temporal.SoarActivityImpl.*(..))"
            + " && @annotation(org.springframework.transaction.annotation.Transactional)")
    public Object withActivityTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        String tenantId = tenantId(joinPoint.getArgs());
        if (!TenantContext.isValid(tenantId)) {
            return joinPoint.proceed();
        }
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            return joinPoint.proceed();
        }
    }

    private static String tenantId(Object[] args) {
        if (args == null) return null;
        for (Object argument : args) {
            if (argument instanceof SoarRunUpdate update) {
                return update.tenantId();
            }
            if (argument instanceof SoarNodeRequest request) {
                return request.tenantId();
            }
        }
        // Lifecycle Activities use tenantId as their first String argument.
        // Do not guess from later strings (runId/nodeId are not tenant IDs).
        if (args.length > 0 && args[0] instanceof String value) {
            return value;
        }
        return null;
    }
}
