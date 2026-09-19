package com.socp.search.config.api.controller;

import com.socp.search.config.domain.ReferenceSet;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceEntryEditingTest {
    @Test
    void removesOnlySelectedEntryWithinTheCurrentTenant() {
        ReferenceSetStore store = new ReferenceSetStore();
        ReferenceSetController controller = new ReferenceSetController(store);
        ReferenceSet set = ReferenceSet.of("review", "", List.of("alice", "bob"));
        TenantContext.runWith("tenant-a", () -> {
            store.add(set);
            assertThat(controller.removeEntry(set.id(), "alice").data()).containsEntry("size", 1);
            assertThat(store.get(set.id()).entries()).containsExactly("bob");
            assertThat(controller.removeEntry(set.id(), "alice").data()).containsEntry("size", 1);
        });
        // Cross-tenant removal must fail on the single error channel (404 via ApiException),
        // never as a data.error success envelope or a raw ResponseStatusException.
        TenantContext.runWith("tenant-b", () -> assertThatThrownBy(() -> controller.removeEntry(set.id(), "bob"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(404));
    }
}
