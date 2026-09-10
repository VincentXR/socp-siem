package com.socp.attack.web.api.controller;
import com.socp.attack.web.domain.Technique;
import com.socp.attack.web.persistence.repository.TechniqueNoteRepository;
import com.socp.attack.web.persistence.store.AttackStore;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
class TechniqueNoteControllerTest {
    @Test void notesAreTenantScopedAndNeverMutateTheStandardCatalog() {
        TechniqueNoteRepository notes = mock(TechniqueNoteRepository.class);
        AttackStore catalog = mock(AttackStore.class);
        when(catalog.technique("T1110")).thenReturn(new Technique("T1110", "Brute Force", "TA0006", "", ""));
        TechniqueNoteController controller = new TechniqueNoteController(new com.socp.attack.web.service.TechniqueNoteService(notes, catalog));
        TenantContext.runWith("tenant-a", () -> assertThat(controller.put("T1110", new com.socp.attack.web.api.request.TechniqueNoteRequest("Investigate"))).containsEntry("note", "Investigate"));
        verify(notes).findByTenantIdAndTechniqueId("tenant-a", "T1110");
        TenantContext.runWith("tenant-b", () -> assertThat(controller.get("T1110")).containsEntry("note", ""));
        verify(notes).findByTenantIdAndTechniqueId("tenant-b", "T1110");
        verify(catalog, never()).update(anyString(), any(), any(), any(), any());
    }
}
