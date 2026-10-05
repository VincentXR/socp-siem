package com.socp.incident.web.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.incident.web.api.request.CaseChangeRequest;
import com.socp.incident.web.api.request.CaseNoteRequest;
import com.socp.incident.web.domain.Case;
import com.socp.incident.web.persistence.repository.CaseMutationRepository;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.service.CaseWorkspaceService;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({CaseStore.class, CaseWorkspaceService.class, CaseWorkspacePersistenceTest.Config.class})
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseWorkspacePersistenceTest {
    @org.springframework.boot.test.mock.mockito.MockBean com.socp.platform.auth.security.OperatorDirectory operatorDirectory;

    @Autowired CaseStore store;
    @Autowired CaseWorkspaceService workspace;
    @Autowired CaseMutationRepository receipts;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @TestConfiguration static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
    @AfterEach void clear() { TenantContext.clear(); }
    Case create() {
        TenantContext.set("workspace-" + java.util.UUID.randomUUID());
        return store.save(Case.create("Investigation", "host-1", "HIGH"));
    }
    CaseChangeRequest change(Case c, String status, String key) {
        return new CaseChangeRequest(status, null, c.rowVersion(), key, null, null, null, null, null);
    }
    Case result(java.util.Map<String, Object> response) { return (Case) response.get("case"); }

    @Test void assignmentViaEitherWorkspaceCommandUsesTheTenantDirectory() {
        Case initial = create();
        org.mockito.Mockito.doThrow(ApiException.badRequest("Owner is not in tenant directory"))
                .when(operatorDirectory).requireAssignable("foreign-user");
        assertThatThrownBy(() -> workspace.assign(initial.id(), "alice", "foreign-user", initial.rowVersion(), "bad-owner"))
                .hasMessageContaining("directory");
        assertThatThrownBy(() -> workspace.change(initial.id(), "alice", new CaseChangeRequest("OPEN", "foreign-user",
                initial.rowVersion(), "bad-change", null, null, null, null, null))).hasMessageContaining("directory");
        assertThat(store.getMetadata(initial.id()).assignee()).isNull();
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isZero();
    }

    @Test void persistsActorStatusAndFreshVersionThenRejectsStaleWrites() {
        Case initial = create();
        Case updated = result(workspace.change(initial.id(), "alice", change(initial, "INVESTIGATING", "first")));
        assertThat(updated.rowVersion()).isGreaterThan(initial.rowVersion());
        assertThat(store.getMetadata(initial.id()).rowVersion()).isEqualTo(updated.rowVersion());
        assertThat(store.timeline(initial.id(), 0, 20).getContent()).singleElement()
                .satisfies(event -> assertThat(event.getMessage()).contains("alice", "OPEN → INVESTIGATING"));
        assertThatThrownBy(() -> workspace.change(initial.id(), "bob", change(initial, "CONTAINED", "stale")))
                .isInstanceOf(ApiException.class).hasMessageContaining("refresh");
        assertThat(store.getMetadata(initial.id()).status()).isEqualTo("INVESTIGATING");
    }

    @Test void noOpReceiptCannotApplyOldValuesAfterAnInterveningChange() {
        Case initial = create();
        CaseChangeRequest noOp = change(initial, "OPEN", "no-op");
        assertThat(workspace.change(initial.id(), "alice", noOp)).containsEntry("changed", false);
        assertThat(store.getMetadata(initial.id()).rowVersion()).isEqualTo(initial.rowVersion());
        workspace.change(initial.id(), "alice", change(initial, "CONTAINED", "later"));
        assertThat(workspace.change(initial.id(), "alice", noOp)).containsEntry("duplicate", true);
        assertThat(store.getMetadata(initial.id()).status()).isEqualTo("CONTAINED");
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test void rejectsKeyReuseAcrossPayloadOrActorAndReplaysChangedCommand() {
        Case initial = create();
        var request = change(initial, "INVESTIGATING", "stable");
        workspace.change(initial.id(), "alice", request);
        assertThat(workspace.change(initial.id(), "alice", request)).containsEntry("duplicate", true);
        assertThatThrownBy(() -> workspace.change(initial.id(), "bob", request)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> workspace.change(initial.id(), "alice", change(initial, "CONTAINED", "stable")))
                .isInstanceOf(ApiException.class);
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test void closureIsRequiredAndVisibleWithOutcomeEvidenceAndRemainingActions() {
        Case initial = create();
        assertThatThrownBy(() -> workspace.change(initial.id(), "alice", change(initial, "CLOSED", "bad-close")))
                .isInstanceOf(ApiException.class).hasMessageContaining("Closure");
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(TenantContext.require(), initial.id(), "bad-close")).isEmpty();
        var closed = new CaseChangeRequest("CLOSED", "alice", initial.rowVersion(), "close", "FALSE_POSITIVE",
                "No compromise", "Approved test", "https://evidence.test/ticket/42", "None");
        workspace.change(initial.id(), "alice", closed);
        assertThat(store.timeline(initial.id(), 0, 20).getContent()).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo("CLOSURE");
            assertThat(event.getMessage()).contains("alice", "FALSE_POSITIVE", "No compromise", "Approved test", "https://evidence.test/ticket/42", "None");
        });
    }

    @Test void notesReplayWithoutDuplicateHistoryAndKeysAreCaseAndTenantScoped() {
        Case initial = create();
        var request = new CaseNoteRequest("Checked evidence", "note-key");
        Case noted = result(workspace.note(initial.id(), "alice", request));
        assertThat(noted.rowVersion()).isGreaterThan(initial.rowVersion());
        assertThat(workspace.note(initial.id(), "alice", request)).containsEntry("duplicate", true);
        assertThatThrownBy(() -> workspace.note(initial.id(), "alice", new CaseNoteRequest("Different", "note-key")))
                .isInstanceOf(ApiException.class);
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isEqualTo(1);
        TenantContext.set("other");
        assertThatThrownBy(() -> workspace.note(initial.id(), "alice", request)).isInstanceOf(ApiException.class);
    }

    @Test void claimAndQueueUseExactOwnerAndCannotOverwriteAnotherOwner() {
        Case initial = create();
        Case claimed = result(workspace.claim(initial.id(), "alice", initial.rowVersion(), "claim"));
        assertThat(claimed.assignee()).isEqualTo("alice");
        assertThat(store.queue(1, 10, "", "", "mine", "alice").getContent()).extracting(Case::id).containsExactly(initial.id());
        assertThat(store.queue(1, 10, "", "", "mine", "bob").getContent()).isEmpty();
        assertThat(store.queue(1, 10, "", "", "unassigned", "alice").getContent()).isEmpty();
        assertThatThrownBy(() -> workspace.claim(initial.id(), "bob", claimed.rowVersion(), "take"))
                .isInstanceOf(ApiException.class).hasMessageContaining("assigned");
        assertThat(workspace.claim(initial.id(), "alice", initial.rowVersion(), "claim")).containsEntry("duplicate", true);
    }
    @Test void outerAuditTransactionFailureRollsBackMetadataHistoryAndReceiptTogether() {
        Case initial = create();
        var request = change(initial, "INVESTIGATING", "rollback");
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
        assertThatThrownBy(() -> transaction.execute(status -> {
            workspace.change(initial.id(), "alice", request);
            throw new IllegalStateException("audit outbox unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.getMetadata(initial.id()).status()).isEqualTo("OPEN");
        assertThat(store.getMetadata(initial.id()).rowVersion()).isEqualTo(initial.rowVersion());
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isZero();
        assertThat(receipts.findByTenantIdAndCaseIdAndRequestKey(TenantContext.require(), initial.id(), "rollback")).isEmpty();
        assertThat(workspace.change(initial.id(), "alice", request)).containsEntry("changed", true);
    }

    @Test void concurrentSameCommandHasOneMutationOneReceiptAndOneReplay() throws Exception {
        Case initial = create();
        String tenant = TenantContext.require();
        var request = change(initial, "INVESTIGATING", "concurrent");
        var start = new java.util.concurrent.CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> command = () -> {
                TenantContext.set(tenant);
                try {
                    start.await();
                    return (Boolean) workspace.change(initial.id(), "alice", request).get("duplicate");
                } finally { TenantContext.clear(); }
            };
            var first = executor.submit(command);
            var second = executor.submit(command);
            assertThat(java.util.List.of(first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(15, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(false, true);
        }
        assertThat(store.getMetadata(initial.id()).status()).isEqualTo("INVESTIGATING");
        assertThat(store.timeline(initial.id(), 0, 20).getTotalElements()).isEqualTo(1);
    }

}
