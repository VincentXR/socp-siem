package com.socp.attack.web.api.controller;

import com.socp.attack.web.api.request.CoverageRequest;
import com.socp.attack.web.domain.Tactic;
import com.socp.attack.web.domain.Technique;
import com.socp.attack.web.persistence.store.AttackStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anySet;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttackControllerTest {

    @Test
    void filtersTechniquesAndReportsLookupAndStats() {
        AttackStore store = mock(AttackStore.class);
        Tactic tactic = new Tactic("TA0001", "Initial Access", 1);
        Technique technique = new Technique("T1110", "Brute Force", "TA0001", "url", "desc");
        when(store.tactics()).thenReturn(List.of(tactic));
        when(store.techniques()).thenReturn(List.of(technique));
        when(store.technique("T1110")).thenReturn(technique);
        AttackController controller = new AttackController(store, 500);

        assertThat(controller.tactics(1, 500).data().items()).isEqualTo(List.of(tactic));
        assertThat(controller.tactics(1, 500).data().total()).isEqualTo(1);
        assertThat(controller.techniques(null, 1, 500).data().items()).containsExactly(technique);
        assertThat(controller.techniques("TA0001", 1, 500).data().items()).containsExactly(technique);
        assertThat(controller.techniques("TA9999", 1, 500).data().items()).isEmpty();
        assertThat(controller.techniques("TA9999", 1, 500).data().total()).isZero();
        assertThat(controller.technique("T1110").data()).containsEntry("found", true)
                .containsEntry("technique", technique);
        assertThat(controller.stats().data()).containsEntry("tactics", 1).containsEntry("techniques", 1);
    }

    @Test
    void rejectsListPageSizeAboveConfiguredLimit() {
        AttackController controller = new AttackController(mock(AttackStore.class), 500);
        assertThatThrownBy(() -> controller.techniques(null, 1, 501))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> controller.tactics(0, 10))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void sharedCatalogHasNoTenantWriteEndpoint() throws Exception {
        AttackStore store = mock(AttackStore.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AttackController(store, 500)).build();
        for (String tenant : List.of("tenant-a", "tenant-b")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put("/api/v1/techniques/T1110").header("X-Tenant-Id", tenant)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"tampered\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
        }
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void delegatesCoverageWithAUniqueSetOfRuleTechniques() {
        AttackStore store = mock(AttackStore.class);
        when(store.coverage(anySet())).thenReturn(Map.of("coverage", 50));
        Map<String, Object> result = new AttackController(store, 500).coverage(
                new CoverageRequest(List.of("T1110", "T1110"))).data();

        assertThat(result).containsEntry("coverage", 50);
        verify(store).coverage(argThat(values -> values.size() == 1 && values.contains("T1110")));
    }
}
