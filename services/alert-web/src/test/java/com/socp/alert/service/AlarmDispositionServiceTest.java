package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;


import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static DispositionEntity entity(String alarmId, String status, String assignee) {
        DispositionEntity entity = new DispositionEntity();
        entity.setAlarmId(alarmId);
        entity.setTenantId("default");
        entity.setStatus(status);
        entity.setAssignee(assignee);
        entity.setNotes("[]");
        return entity;
    }
}
