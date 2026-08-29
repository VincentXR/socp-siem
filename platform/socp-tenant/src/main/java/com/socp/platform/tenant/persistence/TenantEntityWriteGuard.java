package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Enforces the authenticated tenant at the last write boundary.
 *
 * <p>Not every legacy entity can inherit the shared mapped superclass, so the
 * guard intentionally uses the small JavaBean contract ({@code getTenantId}
 * and, when needed, {@code setTenantId}). Entities without that contract are
 * global catalog records and are not tenant-owned. System maintenance must be
 * wrapped in {@link TenantContext#runAsSystem(Runnable)} explicitly.</p>
 */
@Aspect
@Component
@Order(5)
public class TenantEntityWriteGuard {

    @Around("execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.save(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.saveAll(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.saveAndFlush(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.saveAllAndFlush(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.delete(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.deleteAll(..)) || "
            + "execution(* com.socp.platform.tenant.persistence.TenantScopedRepository+.deleteInBatch(..))")
    public Object guard(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!TenantContext.isSystemScope()) {
            String tenant = TenantContext.require();
            for (Object argument : joinPoint.getArgs()) {
                validateArgument(argument, tenant);
            }
        }
        return joinPoint.proceed();
    }

    private static void validateArgument(Object argument, String tenant) {
        if (argument == null) return;
        if (argument instanceof Iterable<?> values) {
            for (Object value : values) validateArgument(value, tenant);
            return;
        }
        Method getter = findMethod(argument.getClass(), "getTenantId");
        if (getter == null) return;
        try {
            Object value = getter.invoke(argument);
            if (value == null || String.valueOf(value).isBlank()) {
                Method setter = findMethod(argument.getClass(), "setTenantId", String.class);
                if (setter == null) {
                    throw new IllegalStateException("Tenant-owned entity has no tenant setter: "
                            + argument.getClass().getName());
                }
                setter.invoke(argument, tenant);
            } else if (!tenant.equals(String.valueOf(value))) {
                throw new IllegalArgumentException("tenant-owned entity does not match authenticated tenant");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unable to validate tenant-owned entity "
                    + argument.getClass().getName(), failure);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
