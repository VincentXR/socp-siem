package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalystClosureEvidenceTest {
    private final DispositionRepository repo = mock(DispositionRepository.class);
    private final AlarmDispositionService service = new AlarmDispositionService(repo);
    private final DispositionEntity row = new DispositionEntity();
    @BeforeEach void setup() {
        TenantContext.set("tenant-a"); row.setAlarmId("alarm-1"); row.setTenantId("tenant-a"); row.setStatus("OPEN"); row.setTags("[\"keep-me\"]");
        when(repo.findForUpdate("alarm-1", "tenant-a")).thenAnswer(inv -> Optional.of(row));
        when(repo.findByAlarmIdAndTenantId("alarm-1", "tenant-a")).thenAnswer(inv -> Optional.of(row));
    }
    @AfterEach void clear() { TenantContext.clear(); }
    @Test void singleAndBatchAnalystClosureRejectMissingOrInvalidEvidenceWithoutWriting() {
        assertThatThrownBy(() -> service.setStatus("alarm-1", " closed ", "", "TRUE_POSITIVE")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.setStatus("alarm-1", "RESOLVED", "verified", "OTHER")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.batchUpdate(List.of("alarm-1"), "RESOLVED", null, "verified", null)).isInstanceOf(ApiException.class);
        verify(repo, never()).save(any());
    }
    @Test void closureClassificationAndReasonAreDurableAndRetriesDoNotDuplicateNotesOrDropTags() {
        var first = service.setStatus("alarm-1", "RESOLVED", "Confirmed with endpoint telemetry", "TRUE_POSITIVE");
        assertThat(first.tags()).containsExactly("keep-me");
        assertThat(first.notes()).extracting(AlarmDispositionService.Disposition.Note::content)
                .containsExactly("Status: OPEN → RESOLVED", "Closure [TRUE_POSITIVE]: Confirmed with endpoint telemetry");
        clearInvocations(repo);
        assertThat(service.setStatus("alarm-1", "RESOLVED", "Confirmed with endpoint telemetry", "TRUE_POSITIVE")).isEqualTo(first);
        verify(repo, never()).save(any());
    }
    @Test void batchClosureCarriesClassificationAndIsReplaySafe() {
        service.batchUpdate(List.of("alarm-1"), "CLOSED", null, "Expected service probe", "BENIGN");
        assertThat(service.get("alarm-1").notes()).extracting(AlarmDispositionService.Disposition.Note::content)
                .containsExactly("Closure [BENIGN]: Expected service probe");
        clearInvocations(repo);
        service.batchUpdate(List.of("alarm-1"), "CLOSED", null, "Expected service probe", "BENIGN");
        verify(repo, never()).save(any());
        assertThat(service.get("alarm-1").tags()).containsExactly("keep-me");
    }
}
