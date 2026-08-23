package com.socp.search.config.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.tenant.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import(TenantCatalogPersistence.class)
class TenantCatalogPersistenceTest {

    @Autowired
    private TenantCatalogPersistence persistence;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void overlaysAndTombstonesAreVisibleToAnotherCatalogInstance() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SinkTarget template = SinkTarget.create("Template", "HTTP", "https://template.example", null, true);
        TenantCatalog<SinkTarget> first = catalog(mapper);
        TenantCatalog<SinkTarget> second = catalog(mapper);
        first.registerTemplate(template);
        second.registerTemplate(template);

        TenantContext.set("tenant-a");
        SinkTarget owned = SinkTarget.create("Owned", "HTTP", "https://owned.example", null, true);
        first.save(owned);

        assertEquals(owned, second.get(owned.id()));
        assertTrue(second.list().stream().anyMatch(item -> item.id().equals(template.id())));

        first.delete(template.id());

        assertNull(second.get(template.id()));
        assertFalse(second.list().stream().anyMatch(item -> item.id().equals(template.id())));

        TenantContext.set("tenant-b");
        assertTrue(second.list().stream().anyMatch(item -> item.id().equals(template.id())));
        assertFalse(second.list().stream().anyMatch(item -> item.id().equals(owned.id())));
    }

    private TenantCatalog<SinkTarget> catalog(ObjectMapper mapper) {
        return new TenantCatalog<>(SinkTarget::id, "sink_target", SinkTarget.class, persistence, mapper);
    }
}
