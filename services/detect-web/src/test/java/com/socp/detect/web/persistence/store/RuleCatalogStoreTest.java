package com.socp.detect.web.persistence.store;

import com.socp.detect.web.model.RuleCatalogMetadata;
import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.detect.web.persistence.repository.RuleContentConflictRepository;
import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.repository.RuleRevisionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleCatalogStoreTest {
    private final RuleRepository repository = mock(RuleRepository.class);
    private RuleSpecStore store;

    @BeforeEach void prepare() {
        // Existing user-owned rules keep catalogue installation outside this query test.
        RuleEntity existing = new RuleEntity();
        existing.setSpec("{\"id\":\"user-rule\",\"name\":\"User rule\"}");
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.of(existing));
        store = RuleSpecStoreFixture.create(repository, mock(RuleRevisionRepository.class), mock(RuleContentConflictRepository.class));
        TenantContext.set("tenant-a");
        store.lookup(List.of());
        clearInvocations(repository);
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void searchBindsAuthenticatedTenantAndLiteralReferenceTokens() {
        when(repository.search(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        store.search(2, 20, "ADMIN_%", "DISABLED", "User_%", "id");
        var page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(eq("tenant-a"), eq("admin_%"), eq("DISABLED"),
                eq(RuleCatalogMetadata.referenceToken("User_%")), eq(RuleCatalogMetadata.referenceToken("id")), page.capture());
        assertEquals(1, page.getValue().getPageNumber());
        assertEquals(20, page.getValue().getPageSize());
        assertEquals("ruleId", page.getValue().getSort().iterator().next().getProperty());
    }

    @Test void compactQueriesUseTheAuthenticatedTenant() {
        when(repository.options(any(), any(), any())).thenReturn(Page.empty());
        when(repository.lookup("tenant-a", List.of("rule"))).thenReturn(List.of(Map.of("id", "rule")));
        store.options(1, 50, "ADMIN");
        assertEquals(List.of(Map.of("id", "rule")), store.lookup(List.of("rule")));
        verify(repository).options(eq("tenant-a"), eq("admin"), any());
        TenantContext.clear();
        assertThrows(IllegalStateException.class, () -> store.lookup(List.of("rule")));
    }

    @Test void activeTechniquesUnionGroupsWithoutDuplicateIds() {
        when(repository.activeTechniques(eq("tenant-a"), any())).thenReturn(List.of("T1110\nT1110.001", "T1078\nT1110"));
        assertEquals(List.of("T1078", "T1110", "T1110.001"), store.activeTechniques());
    }

    @Test void oversizedGroupsFailInsteadOfReturningPartialCoverage() {
        when(repository.activeTechniques(eq("tenant-a"), any())).thenReturn(Collections.nCopies(10_001, "T1110"));
        assertThrows(ApiException.class, store::activeTechniques);
    }

    @Test void oversizedTechniqueUnionFailsInsteadOfReturningPartialCoverage() {
        String group = IntStream.range(0, 10_001).mapToObj(i -> "T" + i).collect(Collectors.joining("\n"));
        when(repository.activeTechniques(eq("tenant-a"), any())).thenReturn(List.of(group));
        assertThrows(ApiException.class, store::activeTechniques);
    }
}
