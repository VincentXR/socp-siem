package com.socp.detect.web.service;

import com.socp.detect.web.persistence.store.RuleSpecStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionRuleServiceTest {
    @Mock RuleSpecStore store;
    @Mock RuleChangePublisher publisher;
    @Mock DetectionEngineLifecycle lifecycle;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void catalogueQueriesHaveNoEngineSideEffects() {
        var result = new org.springframework.data.domain.PageImpl<Map<String, Object>>(
                List.of(Map.of("id", "rule-a", "status", "ACTIVE")));
        when(store.byTechnique("T1110", 2, 20)).thenReturn(result);
        var service = new DetectionRuleService(store, publisher, lifecycle);

        assertEquals(result, service.rulesByTechnique("T1110", 2, 20));

        verifyNoInteractions(lifecycle, publisher);
    }

    @Test
    void successfulWritePublishesBeforeReloadAndOnlyAfterCommit() {
        var saved = Map.<String, Object>of("id", "rule-a");
        when(store.create(saved)).thenReturn(saved);
        var service = new DetectionRuleService(store, publisher, lifecycle);
        TransactionSynchronizationManager.initSynchronization();

        assertEquals(saved, service.addRule(saved));

        verify(publisher).publish("rule-a", "add");
        verifyNoInteractions(lifecycle);
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, callbacks.size());
        callbacks.getFirst().afterCommit();
        var order = inOrder(store, publisher, lifecycle);
        order.verify(store).create(saved);
        order.verify(publisher).publish("rule-a", "add");
        order.verify(lifecycle).reload();
    }

    @Test
    void rolledBackWriteNeverReloadsLiveState() {
        var saved = Map.<String, Object>of("id", "rule-a");
        when(store.activate("rule-a", null)).thenReturn(saved);
        var service = new DetectionRuleService(store, publisher, lifecycle);
        TransactionSynchronizationManager.initSynchronization();

        service.activateRule("rule-a");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(lifecycle);
    }

    @Test
    void committedReloadRequiresANewTransactionWhileWriteResourcesRemainBound() {
        var saved = Map.<String, Object>of("id", "rule-a");
        when(store.activate("rule-a", null)).thenReturn(saved);
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var service = new DetectionRuleService(store, publisher, lifecycle);
        service.configureCommittedReloadTransaction(manager);
        TransactionSynchronizationManager.initSynchronization();

        service.activateRule("rule-a");
        verifyNoInteractions(manager, lifecycle);
        TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();

        var order = inOrder(manager, lifecycle);
        order.verify(manager).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(lifecycle).reload();
        order.verify(manager).commit(status);
    }

    @Test
    void absentDeleteAndFailedWritesDoNotPublishOrScheduleReload() {
        when(store.delete("missing")).thenReturn(false);
        when(store.activate("missing", null)).thenThrow(new IllegalArgumentException("missing"));
        var service = new DetectionRuleService(store, publisher, lifecycle);
        TransactionSynchronizationManager.initSynchronization();

        assertEquals(false, service.deleteRule("missing"));
        assertThrows(IllegalArgumentException.class, () -> service.activateRule("missing"));

        assertEquals(List.of(), TransactionSynchronizationManager.getSynchronizations());
        verifyNoInteractions(publisher, lifecycle);
    }

    @Test
    void outboxFailureDoesNotReloadBeforeTheWriteRollsBack() {
        var saved = Map.<String, Object>of("id", "rule-a");
        when(store.create(saved)).thenReturn(saved);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox unavailable"))
                .when(publisher).publish("rule-a", "add");
        var service = new DetectionRuleService(store, publisher, lifecycle);
        TransactionSynchronizationManager.initSynchronization();

        assertThrows(IllegalStateException.class, () -> service.addRule(saved));

        assertEquals(List.of(), TransactionSynchronizationManager.getSynchronizations());
        verify(lifecycle, never()).reload();
    }

    @Test
    void everyPublicMutationRetainsItsSpringTransactionBoundary() {
        for (var method : DetectionRuleService.class.getDeclaredMethods()) {
            if (List.of("addRule", "updateRule", "activateRule", "deleteRule", "restoreRuleRevision")
                    .contains(method.getName())) {
                assertNotNull(method.getAnnotation(Transactional.class), method.toString());
            }
        }
    }
}
