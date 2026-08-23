package com.socp.platform.data;

import com.socp.platform.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BaseEntityTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void lifecycleUsesTenantContextAndMaintainsTimestamps() {
        TenantContext.set("tenant-a");
        TestEntity entity = new TestEntity();

        entity.onCreate();
        Instant created = entity.getCreatedAt();

        assertThat(entity.getTenantId()).isEqualTo("tenant-a");
        assertThat(entity.getUpdatedAt()).isEqualTo(created);
        entity.onUpdate();
        assertThat(entity.getUpdatedAt()).isAfterOrEqualTo(created);
    }

    @Test
    void explicitTenantIsNeverOverwritten() {
        TenantContext.set("tenant-a");
        TestEntity entity = new TestEntity();
        entity.setTenantId("tenant-b");
        entity.onCreate();
        assertThat(entity.getTenantId()).isEqualTo("tenant-b");
    }

    private static final class TestEntity extends BaseEntity {
    }
}
