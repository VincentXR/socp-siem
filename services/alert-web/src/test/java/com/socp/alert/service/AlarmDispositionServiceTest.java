package com.socp.alert.service;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlarmDispositionServiceTest {

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
