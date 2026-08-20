package com.socp.detect.web.store;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import static org.mockito.ArgumentMatchers.any;

class RuleSpecStoreTest {

    @Test
    void seedsDefaultRulesWithValidJson() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.count()).thenReturn(0L);

        new RuleSpecStore(repository);

        verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(RuleEntity.class));
    }

    @Test
    void upgradesAnOlderPackagedRuleToTheCurrentContentVersion() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity old = entity("AUTH-BRUTE", """
                {"id":"AUTH-BRUTE","contentPack":"socp-core-detections","contentVersion":"2026.08.19"}
                """);
        when(repository.count()).thenReturn(1L);
        when(repository.findByIdAndTenantId(any(), any())).thenAnswer(invocation ->
                "AUTH-BRUTE".equals(invocation.getArgument(0)) ? Optional.of(old) : Optional.empty());

        new RuleSpecStore(repository);

        ArgumentCaptor<RuleEntity> saved = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getAllValues().stream()
                .map(RuleEntity::getSpec)
                .anyMatch(spec -> spec.contains("\"id\":\"AUTH-BRUTE\"")
                        && spec.contains("\"contentVersion\":\"2026.08.20\"")));
    }

    @Test
    void doesNotOverwriteAUserOwnedRuleWithACollidingId() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity userRule = entity("AUTH-BRUTE", "{\"id\":\"AUTH-BRUTE\",\"owner\":\"local-user\"}");
        when(repository.count()).thenReturn(1L);
        when(repository.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(userRule));

        new RuleSpecStore(repository);

        verify(repository, never()).save(any(RuleEntity.class));
    }

    @Test
    void concurrentPackagedRuleInstallIsIdempotent() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity installed = entity("AUTH-BRUTE", """
                {"id":"AUTH-BRUTE","contentPack":"socp-core-detections","contentVersion":"2026.08.20"}
                """);
        when(repository.count()).thenReturn(1L);
        when(repository.findByIdAndTenantId(any(), any()))
                .thenReturn(Optional.empty(), Optional.of(installed))
                .thenReturn(Optional.of(installed));
        when(repository.save(any(RuleEntity.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent insert"));

        assertDoesNotThrow(() -> new RuleSpecStore(repository));
    }

    private static RuleEntity entity(String id, String spec) {
        RuleEntity entity = new RuleEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setSpec(spec);
        return entity;
    }
}
