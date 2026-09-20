package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.RuleRepository;
import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;

class RuleSpecStoreTest {

    @Test
    void seedsDefaultRulesWithValidJson() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.countByTenantId("default")).thenReturn(0L);

        new RuleSpecStore(repository);

        verify(repository, org.mockito.Mockito.atLeastOnce()).save(any(RuleEntity.class));
    }

    @Test
    void upgradesAnOlderPackagedRuleToTheCurrentContentVersion() {
        String currentPackVersion = String.valueOf(DetectionContentCatalog.manifest().get("version"));
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity old = entity("AUTH-BRUTE", """
                {"id":"AUTH-BRUTE","contentPack":"socp-core-detections","contentVersion":"2026.08.19"}
                """);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenAnswer(invocation ->
                "AUTH-BRUTE".equals(invocation.getArgument(0)) ? Optional.of(old) : Optional.empty());

        new RuleSpecStore(repository);

        ArgumentCaptor<RuleEntity> saved = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertTrue(saved.getAllValues().stream()
                .map(RuleEntity::getSpec)
                .anyMatch(spec -> spec.contains("\"id\":\"AUTH-BRUTE\"")
                        && spec.contains("\"contentVersion\":\"" + currentPackVersion + "\"")));
    }

    @Test
    void doesNotOverwriteAUserOwnedRuleWithACollidingId() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity userRule = entity("AUTH-BRUTE", "{\"id\":\"AUTH-BRUTE\",\"owner\":\"local-user\"}");
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.of(userRule));

        new RuleSpecStore(repository);

        verify(repository, never()).save(any(RuleEntity.class));
    }

    @Test
    void customizedPackagedRuleIsNotOverwrittenByANewerPack() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity customized = entity("AUTH-BRUTE", """
                {"id":"AUTH-BRUTE","contentPack":"socp-core-detections",
                 "contentVersion":"2026.08.19","contentCustomized":true,
                 "status":"DISABLED","enabled":false}
                """);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.of(customized));

        new RuleSpecStore(repository);

        verify(repository, never()).save(any(RuleEntity.class));
    }

    @Test
    void userEditOfPackagedRuleOptsOutOfFutureAutomaticReplacement() {
        String currentPackVersion = String.valueOf(DetectionContentCatalog.manifest().get("version"));
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return Optional.of(entity(id, "{\"id\":\"" + id
                    + "\",\"contentPack\":\"socp-core-detections\","
                    + "\"contentVersion\":\"" + currentPackVersion + "\"}"));
        });
        RuleSpecStore store = new RuleSpecStore(repository);
        clearInvocations(repository);

        @SuppressWarnings("unchecked")
        Map<String, Object> packaged = (Map<String, Object>) ((java.util.List<Map<String, Object>>)
                DetectionContentCatalog.manifest().get("rules")).stream()
                .filter(item -> "AUTH-BRUTE".equals(String.valueOf(item.get("id"))))
                .findFirst().orElseThrow().get("spec");
        Map<String, Object> edited = new LinkedHashMap<>(packaged);
        edited.put("status", "DISABLED");
        edited.put("enabled", false);

        TenantContext.set("default");
        try {
            store.save(edited);
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<RuleEntity> saved = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository).save(saved.capture());
        Map<String, Object> persisted = com.socp.rule.util.Json.parseObject(saved.getValue().getSpec());
        assertEquals(Boolean.TRUE, persisted.get("contentCustomized"));
        assertEquals("socp-core-detections", persisted.get("contentPack"));
        assertEquals(currentPackVersion, persisted.get("contentVersion"));
        assertEquals("DISABLED", persisted.get("status"));
    }

    @Test
    void userCreatedRuleWithPackagedIdIsNotClaimedByTheContentPack() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.countByTenantId("default")).thenReturn(1L);
        String currentPackVersion = String.valueOf(DetectionContentCatalog.manifest().get("version"));
        when(repository.findByRuleIdAndTenantId(any(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if ("AUTH-BRUTE".equals(id)) return Optional.empty();
            return Optional.of(entity(id, "{\"id\":\"" + id
                    + "\",\"contentPack\":\"socp-core-detections\","
                    + "\"contentVersion\":\"" + currentPackVersion + "\"}"));
        });
        RuleSpecStore store = new RuleSpecStore(repository);
        clearInvocations(repository);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "AUTH-BRUTE");
        user.put("name", "local collision");
        user.put("type", "pattern");
        user.put("severity", "HIGH");
        user.put("version", "1");
        user.put("owner", "local-user");
        user.put("status", "TESTING");
        user.put("match", java.util.List.of(Map.of("field", "msg", "op", "contains", "value", "local")));

        TenantContext.set("default");
        try {
            store.save(user);
        } finally {
            TenantContext.clear();
        }

        ArgumentCaptor<RuleEntity> saved = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository).save(saved.capture());
        Map<String, Object> persisted = com.socp.rule.util.Json.parseObject(saved.getValue().getSpec());
        assertTrue(!persisted.containsKey("contentPack"));
        assertTrue(!persisted.containsKey("contentVersion"));
        assertEquals(Boolean.TRUE, persisted.get("contentCustomized"));
    }

    @Test
    void concurrentPackagedRuleInstallIsIdempotent() {
        String currentPackVersion = String.valueOf(DetectionContentCatalog.manifest().get("version"));
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity installed = entity("AUTH-BRUTE", "{\"id\":\"AUTH-BRUTE\",\"contentPack\":\"socp-core-detections\",\"contentVersion\":\""
                + currentPackVersion + "\"}");
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any()))
                .thenReturn(Optional.empty(), Optional.of(installed))
                .thenReturn(Optional.of(installed));
        when(repository.save(any(RuleEntity.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent insert"));

        assertDoesNotThrow(() -> new RuleSpecStore(repository));
    }

    @Test
    void rejectsCrossEntityGroupingAsAClientContractError() {
        RuleRepository repository = mock(RuleRepository.class);
        RuleEntity userRule = entity("user-owned", "{\"id\":\"user-owned\",\"owner\":\"local-user\"}");
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.of(userRule));
        RuleSpecStore store = new RuleSpecStore(repository);

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("id", "cross-entity");
        invalid.put("name", "cross-entity");
        invalid.put("type", "threshold");
        invalid.put("severity", "HIGH");
        invalid.put("version", "1");
        invalid.put("owner", "analyst");
        invalid.put("groupBy", "user");
        invalid.put("routingField", "host");
        invalid.put("threshold", 2);

        TenantContext.set("default");
        try {
            ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> store.save(invalid));
            assertEquals(400, failure.getCode());
            assertTrue(failure.getMessage().contains("cross-entity grouping is unsupported"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void rejectsAWindowTheEngineCannotParseWithoutPersistingIt() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        RuleSpecStore store = new RuleSpecStore(repository);
        Map<String, Object> spec = windowRule("1w");

        TenantContext.set("default");
        try {
            ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                    () -> store.save(spec));
            assertEquals(400, failure.getCode());
            assertTrue(failure.getMessage().contains("invalid window"), failure.getMessage());
        } finally {
            TenantContext.clear();
        }
        // The write path compiles with the engine's own parser before storing, so
        // a document that could stop the tenant's detection never reaches a row.
        ArgumentCaptor<RuleEntity> saved = ArgumentCaptor.forClass(RuleEntity.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream()
                .noneMatch(row -> String.valueOf(row.getSpec()).contains("\"id\":\"window-rule\"")),
                "未通过校验的文档不得入库");
    }

    @Test
    void storesAnInternallyConsistentRuleWhoseGroupingCanLoseToTheRoutingDimension() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.countByTenantId("default")).thenReturn(1L);
        when(repository.findByRuleIdAndTenantId(any(), any())).thenReturn(Optional.empty());
        RuleSpecStore store = new RuleSpecStore(repository);
        Map<String, Object> spec = windowRule("60s");
        // groupBy/keyField/routingField agree inside the document, so persistence
        // cannot see the cross-dimension risk; it advises instead of rejecting,
        // because rejecting would also reject packaged content.
        spec.put("groupBy", "user");
        spec.put("keyField", "user");
        spec.put("routingField", "user");
        spec.put("dataSources", java.util.List.of("auth"));

        TenantContext.set("default");
        try {
            assertDoesNotThrow(() -> store.save(spec));
        } finally {
            TenantContext.clear();
        }
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(row ->
                String.valueOf(row.getSpec()).contains("\"id\":\"window-rule\"")
                        && String.valueOf(row.getSpec()).contains("\"groupBy\":\"user\"")));
    }

    private static Map<String, Object> windowRule(String window) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", "window-rule");
        spec.put("name", "window-rule");
        spec.put("type", "threshold");
        spec.put("severity", "HIGH");
        spec.put("version", "1");
        spec.put("owner", "analyst");
        spec.put("groupBy", "host");
        spec.put("keyField", "host");
        spec.put("routingField", "host");
        spec.put("threshold", 2);
        spec.put("window", window);
        return spec;
    }

    private static RuleEntity entity(String id, String spec) {
        RuleEntity entity = new RuleEntity();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setSpec(spec);
        return entity;
    }
}
