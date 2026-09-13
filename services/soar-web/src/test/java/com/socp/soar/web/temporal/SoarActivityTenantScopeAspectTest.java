package com.socp.soar.web.temporal;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SoarActivityTenantScopeAspectTest {

    private final SoarActivityTenantScopeAspect aspect = new SoarActivityTenantScopeAspect();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void installsTenantBeforeTransactionalActivityProceeds() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"tenant-a", "run-1"});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals("tenant-a", TenantContext.get());
            return "ok";
        });

        assertEquals("ok", aspect.withActivityTenant(joinPoint));
        assertNull(TenantContext.get());
    }

    @Test
    void preservesExistingTenantAndUsesStructuredRequests() throws Throwable {
        TenantContext.set("outer");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{
                new SoarRunUpdate("tenant-b", "run-1", "SUCCEEDED", "{}", null, null)
        });
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals("tenant-b", TenantContext.get());
            return joinPoint;
        });

        assertSame(joinPoint, aspect.withActivityTenant(joinPoint));
        assertEquals("outer", TenantContext.get());
    }

    @Test
    void proceedsWithoutScopeWhenArgumentsDoNotCarryTenant() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{42});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertNull(TenantContext.get());
            return Boolean.TRUE;
        });

        assertEquals(Boolean.TRUE, aspect.withActivityTenant(joinPoint));
    }
}
