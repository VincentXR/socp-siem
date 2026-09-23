package com.socp.search.config.persistence.store;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinkTargetStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void limitsTenantTargetsAndAllowsReplacementAfterDeletion() {
        SinkTargetStore store = new SinkTargetStore();
        TenantContext.set("tenant-a");
        for (int index = 0; index < 128; index++) store.save(target("target-" + index));

        assertEquals(400, assertThrows(ApiException.class,
                () -> store.save(target("overflow"))).getCode());
        assertTrue(store.delete(store.list().getFirst().id()));
        store.save(target("replacement"));
        assertEquals(128, store.list().size());

        TenantContext.set("tenant-b");
        assertTrue(store.list().isEmpty());
        store.save(target("independent"));
        assertEquals(1, store.list().size());
        assertFalse(store.delete(SinkTargetStore.PLATFORM_INGEST_ID));
    }

    private static SinkTarget target(String name) {
        return SinkTarget.create(name, "HTTP", "https://example.test/ingest", null, true);
    }
}
