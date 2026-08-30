package com.socp.search.config.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.FieldDef;
import com.socp.search.config.domain.LogCategory;
import com.socp.search.config.domain.ReferenceSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogStoreCoverageTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-catalog-tests");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void referenceSetsSupportLookupAndTenantOverrides() {
        ReferenceSetStore store = new ReferenceSetStore();
        ReferenceSet seeded = store.list().getFirst();

        assertThat(store.get(seeded.id())).isEqualTo(seeded);
        assertThat(store.contains(seeded.name(), seeded.entries().getFirst().toUpperCase())).isTrue();
        assertThat(store.contains(seeded.name(), "not-present")).isFalse();
        assertThat(store.matchedSets(seeded.entries().getFirst()))
                .contains(seeded.name());
        assertThat(store.matchedSets(null)).isEmpty();

        ReferenceSet custom = ReferenceSet.of("custom", "custom lookup", List.of("value-1"));
        assertThat(store.add(custom)).isEqualTo(custom);
        assertThat(store.list()).contains(custom);
        assertThat(store.delete(custom.id())).isTrue();
        assertThat(store.get(custom.id())).isNull();
    }

    @Test
    void metadataStoresExposeSeedsAndAllowTenantScopedChanges() {
        FieldDefStore fields = new FieldDefStore();
        FieldDef customField = FieldDef.create("custom_field", "Custom", "string",
                "custom", true, true, true, "test field");
        assertThat(fields.list()).isNotEmpty();
        assertThat(fields.save(customField)).isEqualTo(customField);
        assertThat(fields.list()).contains(customField);
        assertThat(fields.delete(customField.id())).isTrue();

        DataSourceTypeStore sourceTypes = new DataSourceTypeStore();
        DataSourceType customType = DataSourceType.create("CUSTOM", "Custom source", "test", true);
        assertThat(sourceTypes.list()).isNotEmpty();
        assertThat(sourceTypes.save(customType)).isEqualTo(customType);
        assertThat(sourceTypes.list()).contains(customType);
        assertThat(sourceTypes.delete(customType.id())).isTrue();

        LogCategoryStore categories = new LogCategoryStore();
        LogCategory customCategory = LogCategory.create("CUSTOM", "Custom category", "test", "LOW", true);
        assertThat(categories.list()).isNotEmpty();
        assertThat(categories.save(customCategory)).isEqualTo(customCategory);
        assertThat(categories.list()).contains(customCategory);
        assertThat(categories.delete(customCategory.id())).isTrue();
    }
}
