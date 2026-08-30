package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void systemScopeRequiresTheExplicitMarker() throws Exception {
        Method reviewed = ExampleJobs.class.getDeclaredMethod("reviewed");
        Method ordinary = ExampleJobs.class.getDeclaredMethod("ordinary");

        assertTrue(reviewed.isAnnotationPresent(TenantSystemJob.class));
        assertFalse(ordinary.isAnnotationPresent(TenantSystemJob.class));
    }

    private static final class ExampleJobs {
        @TenantSystemJob
        void reviewed() {
        }

        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1_000)
        void ordinary() {
        }
    }
}
