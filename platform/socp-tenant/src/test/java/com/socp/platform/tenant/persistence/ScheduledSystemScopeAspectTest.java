package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledSystemScopeAspectTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void scheduledInvocationRunsInExplicitSystemScopeAndRestoresCaller() throws Throwable {
        TenantContext.set("tenant-a");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertTrue(TenantContext.isSystemScope());
            return "done";
        });

        Object result = new ScheduledSystemScopeAspect().withSystemScope(joinPoint);

        assertTrue("done".equals(result));
        assertTrue(!TenantContext.isSystemScope());
        assertTrue("tenant-a".equals(TenantContext.get()));
    }
}
