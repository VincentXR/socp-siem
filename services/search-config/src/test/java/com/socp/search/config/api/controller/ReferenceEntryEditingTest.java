package com.socp.search.config.api.controller;

import com.socp.search.config.domain.ReferenceSet;
import com.socp.search.config.persistence.store.ReferenceSetStore;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
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
            assertThat(controller.removeEntry(set.id(), "alice")).containsEntry("size", 1);
            assertThat(store.get(set.id()).entries()).containsExactly("bob");
            assertThat(controller.removeEntry(set.id(), "alice")).containsEntry("size", 1);
        });
        TenantContext.runWith("tenant-b", () -> assertThatThrownBy(() -> controller.removeEntry(set.id(), "bob")).isInstanceOf(ResponseStatusException.class));
    }
}
