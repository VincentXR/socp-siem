package com.socp.asset.web.persistence.store;

import com.socp.asset.web.domain.Asset;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AssetStore.class)
@TestPropertySource(properties = {"socp.demo-data.enabled=false", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class AssetLookupPersistenceTest {
    @Autowired private AssetStore store;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test
    void exactAssociationsArePagedAndNotLimitedToAnInventoryPrefix() {
        TenantContext.set("asset-association");
        for (int index = 0; index < 505; index++) store.save(Asset.create("a-" + index, "SERVER", "10.0.0.1", "Linux", "sec", "HIGH"));
        var byIp = store.save(Asset.create("zz-ip", "SERVER", "203.0.113.7", "Linux", "sec", "HIGH"));
        var byName = store.save(Asset.create("ZZ-HOST", "SERVER", "203.0.113.8", "Linux", "sec", "HIGH"));
        var both = store.save(Asset.create("zz-host", "SERVER", "203.0.113.7", "Linux", "sec", "HIGH"));
        store.save(Asset.create("zz-host-extra", "SERVER", "203.0.113.70", "Linux", "sec", "HIGH"));
        var first = store.related(1, 2, " 203.0.113.7 ", " zz-host ");
        assertThat(first.getTotalElements()).isEqualTo(3);
        var all = new java.util.ArrayList<>(first.getContent());
        all.addAll(store.related(2, 2, "203.0.113.7", "zz-host").getContent());
        assertThat(all).extracting(Asset::id).containsExactlyInAnyOrder(byIp.id(), byName.id(), both.id());
        assertThat(store.related(1, 20, "", "%")).isEmpty();
        assertThat(store.related(1, 20, "", "")).isEmpty();
        TenantContext.set("other");
        assertThat(store.related(1, 20, "203.0.113.7", "zz-host")).isEmpty();
    }

    @Test
    void directLookupCannotReadAnotherTenantsAsset() {
        TenantContext.set("asset-owner");
        Asset saved = store.save(Asset.create("linked-host", "SERVER", "203.0.113.7", "Linux", "sec", "HIGH"));
        assertThat(store.get(saved.id())).isEqualTo(saved);
        TenantContext.set("different-tenant");
        assertThat(store.get(saved.id())).isNull();
        assertThat(store.get("missing")).isNull();
    }
}
