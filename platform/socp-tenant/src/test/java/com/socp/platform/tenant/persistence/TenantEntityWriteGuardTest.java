package com.socp.platform.tenant.persistence;

import com.socp.platform.tenant.context.TenantContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantEntityWriteGuardTest {

    private final TenantEntityWriteGuard guard = new TenantEntityWriteGuard();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void fillsTenantOnNewEntityBeforeProceeding() throws Throwable {
        TenantContext.set("tenant-a");
        TenantEntity entity = new TenantEntity();
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{entity});
        when(joinPoint.proceed()).thenReturn(entity);

        assertSame(entity, guard.guard(joinPoint));
        assertEquals("tenant-a", entity.getTenantId());
        verify(joinPoint).proceed();
    }

    @Test
    void rejectsCrossTenantEntityBeforePersistence() throws Throwable {
        TenantContext.set("tenant-a");
        TenantEntity entity = new TenantEntity("tenant-b");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{entity});

        assertThrows(IllegalArgumentException.class, () -> guard.guard(joinPoint));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void systemScopeAllowsMaintenanceEntity() throws Throwable {
        TenantEntity entity = new TenantEntity("tenant-b");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{entity});
        when(joinPoint.proceed()).thenReturn(entity);

        TenantContext.runAsSystem(() -> {
            try {
                assertSame(entity, guard.guard(joinPoint));
            } catch (Throwable failure) {
                throw new AssertionError(failure);
            }
        });
        verify(joinPoint).proceed();
    }

    @Test
    void validatesNestedIterableArgumentsAndIgnoresNullValues() throws Throwable {
        TenantContext.set("tenant-a");
        TenantEntity first = new TenantEntity();
        TenantEntity second = new TenantEntity("tenant-a");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{java.util.Arrays.asList(first, null, second)});
        when(joinPoint.proceed()).thenReturn(first);

        assertSame(first, guard.guard(joinPoint));
        assertEquals("tenant-a", first.getTenantId());
        verify(joinPoint).proceed();
    }

    @Test
    void ignoresArgumentsWithoutTenantContract() throws Throwable {
        TenantContext.set("tenant-a");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new Object()});
        when(joinPoint.proceed()).thenReturn("ok");

        assertEquals("ok", guard.guard(joinPoint));
        verify(joinPoint).proceed();
    }

    @Test
    void rejectsTenantEntityWithoutSetter() throws Throwable {
        TenantContext.set("tenant-a");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new ReadOnlyTenantEntity()});

        assertThrows(IllegalStateException.class, () -> guard.guard(joinPoint));
        verify(joinPoint, never()).proceed();
    }

    @Test
    void wrapsTenantGetterReflectionFailures() throws Throwable {
        TenantContext.set("tenant-a");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new BrokenTenantEntity()});

        assertThrows(IllegalStateException.class, () -> guard.guard(joinPoint));
        verify(joinPoint, never()).proceed();
    }

    static final class TenantEntity {
        private String tenantId;

        TenantEntity() {
        }

        TenantEntity(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
    }

    static final class ReadOnlyTenantEntity {
        public String getTenantId() {
            return null;
        }
    }

    static final class BrokenTenantEntity {
        public String getTenantId() {
            throw new IllegalStateException("broken");
        }
    }
}
