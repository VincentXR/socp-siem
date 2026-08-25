package com.socp.attack.web.api.controller;

import com.socp.attack.web.api.request.*;
import com.socp.attack.web.domain.Tactic;
import com.socp.attack.web.domain.Technique;
import com.socp.attack.web.persistence.store.AttackStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AttackControllerTest {

    @Test
    void filtersTechniquesAndReportsLookupAndStats() {
        AttackStore store = mock(AttackStore.class);
        Tactic tactic = new Tactic("TA0001", "Initial Access", 1);
        Technique technique = new Technique("T1110", "Brute Force", "TA0001", "url", "desc");
        when(store.tactics()).thenReturn(List.of(tactic));
        when(store.techniques()).thenReturn(List.of(technique));
        when(store.technique("T1110")).thenReturn(technique);
        AttackController controller = new AttackController(store);

        assertThat(controller.tactics()).isEqualTo(List.of(tactic));
        assertThat(controller.techniques(null)).containsExactly(technique);
        assertThat(controller.techniques("TA0001")).containsExactly(technique);
        assertThat(controller.techniques("TA9999")).isEmpty();
        assertThat(controller.technique("T1110")).containsEntry("found", true)
                .containsEntry("technique", technique);
        assertThat(controller.stats()).containsEntry("tactics", 1).containsEntry("techniques", 1);
    }

    @Test
    void updatesExistingTechniqueAndRejectsUnknownId() {
        AttackStore store = mock(AttackStore.class);
        Technique updated = new Technique("T1110", "Password Spray", "TA0001", "url", "desc");
        when(store.update(eq("T1110"), any(), any(), any(), any())).thenReturn(updated);
        AttackController controller = new AttackController(store);

        assertThat(controller.update("T1110", new TechniqueUpdateRequest(
                "Password Spray", "TA0001", "url", "desc"))).isEqualTo(updated);
        when(store.update(eq("missing"), any(), any(), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> controller.update("missing", new TechniqueUpdateRequest(
                null, null, null, null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void delegatesCoverageWithAUniqueSetOfRuleTechniques() {
        AttackStore store = mock(AttackStore.class);
        when(store.coverage(anySet())).thenReturn(Map.of("coverage", 50));
        Map<String, Object> result = new AttackController(store).coverage(
                new CoverageRequest(List.of("T1110", "T1110")));

        assertThat(result).containsEntry("coverage", 50);
        verify(store).coverage(argThat(values -> values.size() == 1 && values.contains("T1110")));
    }
}
