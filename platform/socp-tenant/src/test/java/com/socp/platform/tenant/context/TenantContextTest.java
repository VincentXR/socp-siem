package com.socp.platform.tenant.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void scopeRestoresPreviousTenantEvenWhenWorkFails() {
        TenantContext.set("outer");

        assertThrows(IllegalStateException.class, () -> TenantContext.runWith("inner", () -> {
            assertThat(TenantContext.require()).isEqualTo("inner");
            throw new IllegalStateException("boom");
        }));

        assertThat(TenantContext.require()).isEqualTo("outer");
    }

    @Test
    void wrapperCarriesTenantToWorkerAndDoesNotLeakIt() {
        TenantContext.set("tenant-a");
        Runnable carried = TenantContext.wrap(() -> assertThat(TenantContext.require()).isEqualTo("tenant-a"));
        TenantContext.clear();

        CompletableFuture.runAsync(carried).join();

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void taskDecoratorCapturesTenantAndRestoresWorkerContext() {
        TenantContext.set("request-tenant");
        Runnable decorated = new TenantTaskDecorator().decorate(
                () -> assertThat(TenantContext.require()).isEqualTo("request-tenant"));
        TenantContext.set("worker-tenant");

        decorated.run();

        assertThat(TenantContext.require()).isEqualTo("worker-tenant");
    }

    @Test
    void systemScopeIsExplicitAndRestoredAfterNestedTenantWork() {
        TenantContext.set("request-tenant");

        try (TenantContext.Scope ignored = TenantContext.openSystem()) {
            assertThat(TenantContext.get()).isNull();
            assertThat(TenantContext.isSystemScope()).isTrue();
            TenantContext.runWith("worker-tenant", () -> {
                assertThat(TenantContext.require()).isEqualTo("worker-tenant");
                assertThat(TenantContext.isSystemScope()).isFalse();
            });
            assertThat(TenantContext.get()).isNull();
            assertThat(TenantContext.isSystemScope()).isTrue();
        }

        assertThat(TenantContext.require()).isEqualTo("request-tenant");
        assertThat(TenantContext.isSystemScope()).isFalse();
    }
}
