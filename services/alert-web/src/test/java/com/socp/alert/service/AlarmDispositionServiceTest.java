package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;


import com.socp.platform.tenant.context.AuthenticatedIdentity;
import com.socp.platform.tenant.context.AuthenticatedIdentityContext;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class AlarmDispositionServiceTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        AuthenticatedIdentityContext.clear();
    }

    @Test
    void readsTheRepositoryOnEveryRequest() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity first = entity("alarm-1", "OPEN", "alice");
        DispositionEntity second = entity("alarm-1", "RESOLVED", "bob");
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default"))
                .thenReturn(Optional.of(first), Optional.of(second));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        assertEquals("alice", service.get("alarm-1").assignee());
        assertEquals("bob", service.get("alarm-1").assignee());
        assertEquals("RESOLVED", service.get("alarm-1").status());
    }

    @Test
    void failedPersistenceDoesNotLeaveAProcessLocalDisposition() {
        DispositionRepository repository = mock(DispositionRepository.class);
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.empty());
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.empty());
        when(repository.save(any(DispositionEntity.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        assertThrows(IllegalStateException.class, () -> service.assign("alarm-1", "alice"));
        assertEquals("OPEN", service.get("alarm-1").status());
        assertNull(service.get("alarm-1").assignee());
    }

    @Test
    void batchUpdateDeduplicatesAndAppendsReasonInStableLockOrder() {
        DispositionRepository repository = mock(DispositionRepository.class);
        when(repository.findForUpdate(any(String.class), org.mockito.ArgumentMatchers.eq("default")))
                .thenReturn(Optional.empty());
        when(repository.findByAlarmIdAndTenantId(any(String.class), org.mockito.ArgumentMatchers.eq("default")))
                .thenReturn(Optional.empty());
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        Map<String, Object> result = service.batchUpdate(
                List.of("alarm-b", "alarm-a", "alarm-b"), "RESOLVED", "alice", "bulk triage");

        assertEquals(2, result.get("updated"));
        assertEquals(List.of("alarm-a", "alarm-b"), result.get("alarmIds"));
        verify(repository, org.mockito.Mockito.times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void batchUpdateRejectsEmptyMutation() {
        AlarmDispositionService service = new AlarmDispositionService(mock(DispositionRepository.class));
        assertThrows(com.socp.platform.error.exception.ApiException.class,
                () -> service.batchUpdate(List.of("alarm-1"), null, " ", null));
    }

    @Test
    void statusReplayIsAnIdempotentNoOp() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "RESOLVED", "alice");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.setStatus("alarm-1", "resolved");

        // Re-applying the current status must not rewrite the disposition row.
        verify(repository, org.mockito.Mockito.never()).save(any(DispositionEntity.class));
    }

    @Test
    void assignToTheCurrentOwnerIsAnIdempotentNoOp() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", "alice");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.assign("alarm-1", "  alice  ");

        verify(repository, org.mockito.Mockito.never()).save(any(DispositionEntity.class));
    }

    @Test
    void connectorNoteRetryIsDurablySetOnce() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", "alice");
        stored.setNoteKeys("[]");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.addNote("alarm-1", "soar", "IOC enriched", "run-1:node-1");
        service.addNote("alarm-1", "soar", "IOC enriched", "run-1:node-1");

        assertEquals(1, service.get("alarm-1").notes().size());
        verify(repository, org.mockito.Mockito.times(1)).save(any(DispositionEntity.class));
    }

    private static DispositionEntity entity(String alarmId, String status, String assignee) {
        DispositionEntity entity = new DispositionEntity();
        entity.setAlarmId(alarmId);
        entity.setTenantId("default");
        entity.setStatus(status);
        entity.setAssignee(assignee);
        entity.setNotes("[]");
        return entity;
    }

    // ---------------------------------------------------------------------------------
    // Batch triage: the recorded reason is durable set-once evidence keyed by the
    // trusted actor plus the normalized mutation, while the requested state stays a
    // state replacement. The response shape (updated/alarmIds/items) is unchanged.
    // ---------------------------------------------------------------------------------

    @Test
    void batchReasonIsRecordedOncePerActorAndMutation() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", null);
        stored.setNoteKeys("[]");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        Map<String, Object> first = service.batchUpdate(
                List.of("alarm-1"), "RESOLVED", "alice", "bulk triage");
        Map<String, Object> replay = service.batchUpdate(
                List.of("alarm-1"), "RESOLVED", "alice", "bulk triage");

        List<AlarmDispositionService.Disposition.Note> notes = service.get("alarm-1").notes();
        assertEquals(1, notes.size(),
                "an at-least-once retry of the same batch must not duplicate triage evidence");
        assertEquals("bulk triage", notes.getFirst().content());
        assertEquals(List.of("batch:" + sha256(
                        "actor=operator\nstatus=RESOLVED\nassignee=alice\nreason=bulk triage")),
                noteKeysOf(stored), "the ledger holds exactly the one deterministic identity");
        assertEquals(1, first.get("updated"));
        assertEquals(1, replay.get("updated"),
                "the response stays a receipt of the requested work, not of the write count");
        assertEquals(true, itemsOf(replay).getFirst().get("reasonRecorded"));
        assertEquals("RESOLVED", itemsOf(replay).getFirst().get("status"));
        verify(repository, org.mockito.Mockito.times(1)).save(any(DispositionEntity.class));
    }

    @Test
    void batchNoteAuthorComesFromTheAuthenticatedPrincipal() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", null);
        stored.setNoteKeys("[]");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("analyst-9", "default", "analyst",
                Set.of(), Set.of(), AuthenticatedIdentity.Kind.USER));
        service.batchUpdate(List.of("alarm-1"), "RESOLVED", null, "confirmed true positive");
        // A machine identity has no delegation field on this endpoint, so its own
        // subject is recorded; the audit entry stays the evidence of the call.
        AuthenticatedIdentityContext.set(new AuthenticatedIdentity("soar-web", "default", "service",
                Set.of(), Set.of(), AuthenticatedIdentity.Kind.SERVICE));
        service.batchUpdate(List.of("alarm-1"), "RESOLVED", null, "confirmed true positive");

        assertEquals(List.of("analyst-9", "soar-web"),
                service.get("alarm-1").notes().stream()
                        .map(AlarmDispositionService.Disposition.Note::author).toList(),
                "different actors must each keep their own triage evidence");
        assertEquals(2, noteKeysOf(stored).size(),
                "the identity is actor-scoped, so one actor cannot consume another actor's replay slot");
        verify(repository, org.mockito.Mockito.times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void aBlankReasonRecordsNoNoteAndNoLedgerKey() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", "alice");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        Map<String, Object> result = service.batchUpdate(List.of("alarm-1"), "CLOSED", null, "   ");

        assertTrue(service.get("alarm-1").notes().isEmpty(),
                "a batch without a reason must not invent note text; the audit entry is the evidence");
        assertNull(stored.getNoteKeys(), "no reason means nothing to record in the set-once ledger");
        assertEquals(false, itemsOf(result).getFirst().get("reasonRecorded"));
        assertEquals("CLOSED", itemsOf(result).getFirst().get("status"));
        assertEquals("alice", itemsOf(result).getFirst().get("assignee"));
        verify(repository).save(any(DispositionEntity.class));
    }

    @Test
    void aStateOnlyBatchReplayDoesNotRewriteTheRow() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-a", "OPEN", null);
        when(repository.findForUpdate("alarm-a", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.batchUpdate(List.of("alarm-a", "alarm-a"), "INVESTIGATING", "bob", null);
        Map<String, Object> replay = service.batchUpdate(List.of("alarm-a"), "investigating", " bob ", null);

        // One de-duplicated id, one write: the replay carries no new note and the row
        // already holds the requested state, so updated_at must not churn.
        verify(repository, org.mockito.Mockito.times(1)).save(any(DispositionEntity.class));
        assertEquals(1, replay.get("updated"));
        assertEquals(List.of("alarm-a"), replay.get("alarmIds"));
        assertEquals("INVESTIGATING", stored.getStatus());
        assertEquals("bob", stored.getAssignee());
        assertNull(stored.getNoteKeys());
    }

    @Test
    void aReasonReplayStillReconcilesDriftedStateWithoutADuplicateNote() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", null);
        stored.setNoteKeys("[]");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.batchUpdate(List.of("alarm-1"), "RESOLVED", "alice", "duplicate of AL-1");
        // Somebody else re-opened the alarm in between; the replay must still land.
        stored.setStatus("OPEN");
        Map<String, Object> replay = service.batchUpdate(
                List.of("alarm-1"), "RESOLVED", "alice", "duplicate of AL-1");

        assertEquals("RESOLVED", stored.getStatus(), "the requested state is reconciled on replay");
        assertEquals(1, service.get("alarm-1").notes().size(), "the reason stays recorded exactly once");
        assertEquals(1, noteKeysOf(stored).size());
        assertEquals("RESOLVED", itemsOf(replay).getFirst().get("status"),
                "the reported status is the state the row now holds");
        verify(repository, org.mockito.Mockito.times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void batchLocksEveryIdInLexicalOrderThroughTheLockedRead() {
        DispositionRepository repository = mock(DispositionRepository.class);
        when(repository.findForUpdate(any(String.class), org.mockito.ArgumentMatchers.eq("default")))
                .thenReturn(Optional.empty());
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.batchUpdate(List.of("alarm-c", "alarm-a", "alarm-b"), "CLOSED", null, null);

        InOrder locks = Mockito.inOrder(repository);
        locks.verify(repository).findForUpdate("alarm-a", "default");
        locks.verify(repository).findForUpdate("alarm-b", "default");
        locks.verify(repository).findForUpdate("alarm-c", "default");
        verify(repository, org.mockito.Mockito.times(3)).save(any(DispositionEntity.class));
    }

    @Test
    void theNoteKeyLedgerStaysBoundedByEvictingTheOldestIdentity() {
        DispositionRepository repository = mock(DispositionRepository.class);
        DispositionEntity stored = entity("alarm-1", "OPEN", "alice");
        List<String> full = new java.util.ArrayList<>();
        for (int index = 0; index < 2048; index++) full.add("run-" + index);
        stored.setNoteKeys("[\"" + String.join("\",\"", full) + "\"]");
        when(repository.findForUpdate("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.findByAlarmIdAndTenantId("alarm-1", "default")).thenReturn(Optional.of(stored));
        when(repository.save(any(DispositionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AlarmDispositionService service = new AlarmDispositionService(repository);

        service.addNote("alarm-1", "soar", "IOC enriched", "run-2048");

        List<String> keys = noteKeysOf(stored);
        assertEquals(2048, keys.size(), "the TEXT ledger column must stay bounded");
        assertFalse(keys.contains("run-0"), "the oldest identity is the one dropped");
        assertTrue(keys.contains("run-2048"), "the newest identity must always be retained");
        assertTrue(keys.contains("run-1"), "only one entry may be evicted per write");
        assertEquals(1, service.get("alarm-1").notes().size());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("items");
    }

    private static List<String> noteKeysOf(DispositionEntity entity) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    entity.getNoteKeys(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception failure) {
            throw new AssertionError("note keys must stay a JSON string array: " + entity.getNoteKeys(), failure);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
