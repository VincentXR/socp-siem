package com.socp.search.config.persistence.store;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCatalogStoreTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void templateOverridesAndUserEntriesAreTenantScoped() {
        SinkTargetStore store = new SinkTargetStore();
        String templateId = store.list().getFirst().id();

        TenantContext.set("tenant-a");
        store.delete(templateId);
        SinkTarget custom = SinkTarget.create("tenant-a-only", "HTTP", "https://a.example", null, true);
        store.save(custom);
        assertFalse(store.list().stream().anyMatch(target -> templateId.equals(target.id())));

        TenantContext.set("tenant-b");
        assertTrue(store.list().stream().anyMatch(target -> templateId.equals(target.id())));
        assertFalse(store.list().stream().anyMatch(target -> custom.id().equals(target.id())));
    }
}
