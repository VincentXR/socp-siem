package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Transition-graph enforcement of the alarm lifecycle. Illegal moves are a
 * conflict against the stored state, not a format error, so the analyst sees
 * which targets are currently legal.
 */
@ExtendWith(MockitoExtension.class)
class AlarmDispositionTransitionTest {

    @Mock
    private DispositionRepository repository;

    private AlarmDispositionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new AlarmDispositionService(repository);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private DispositionEntity storedAs(String status) {
        DispositionEntity entity = new DispositionEntity();
        entity.setAlarmId("a1");
        entity.setTenantId("tenant-a");
        entity.setStatus(status);
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        lenient().when(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).thenReturn(Optional.of(entity));
        return entity;
    }

    @Test
    void rejectsClosingThenDowngradingWithConflictAndWritesNothing() {
        storedAs("CLOSED");
        assertThatThrownBy(() -> service.setStatus("a1", "RESOLVED"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(409);
                    assertThat(ex.getMessage()).contains("CLOSED").contains("INVESTIGATING");
                });
        verify(repository, never()).save(any());
    }

    @Test
    void allowsReopenIntoInvestigating() {
        DispositionEntity entity = storedAs("CLOSED");
        AlarmDispositionService.Disposition result = service.setStatus("a1", "INVESTIGATING");
        assertThat(result.status()).isEqualTo("INVESTIGATING");
        assertThat(entity.getStatus()).isEqualTo("INVESTIGATING");
        verify(repository).save(entity);
    }

    @Test
    void reApplyingTheCurrentStateIsANoOpEvenOnTerminalStates() {
        storedAs("CLOSED");
        assertThat(service.setStatus("a1", "closed").status()).isEqualTo("CLOSED");
        verify(repository, never()).save(any());
    }

    @Test
    void unknownVocabularyRemainsABadRequest() {
        assertThatThrownBy(() -> service.setStatus("a1", "ACTIVE"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
    }

    @Test
    void batchAbortsBeforeWritingAnythingWhenAnAlarmCannotMakeTheMove() {
        // IDs are locked in lexical order, so the illegal alarm is reached first and
        // nothing is written for the rest. The @Transactional boundary is what turns
        // an abort midway through a larger selection into a full rollback.
        DispositionEntity closed = new DispositionEntity();
        closed.setAlarmId("a1");
        closed.setTenantId("tenant-a");
        closed.setStatus("CLOSED");
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.batchUpdate(List.of("a1", "a2"), "RESOLVED", null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
        verify(repository, never()).save(any());
    }
}
