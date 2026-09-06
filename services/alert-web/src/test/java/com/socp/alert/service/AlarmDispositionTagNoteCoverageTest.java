package com.socp.alert.service;

import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.alert.persistence.repository.DispositionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Tag-application and note-idempotency behaviour of the disposition service,
 * including tenant scoping of every repository access.
 */
@ExtendWith(MockitoExtension.class)
class AlarmDispositionTagNoteCoverageTest {

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

    private static DispositionEntity stored() {
        DispositionEntity entity = new DispositionEntity();
        entity.setAlarmId("a1");
        entity.setTenantId("tenant-a");
        entity.setStatus("OPEN");
        entity.setAssignee("alice");
        entity.setNotes("[]");
        entity.setTags("[]");
        entity.setNoteKeys("[]");
        return entity;
    }

    @Test
    void addTagAppliesAndDeduplicatesCaseInsensitively() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.addTag("a1", "Web-Server");
        service.addTag("a1", "web-server");

        assertThat(service.get("a1").tags()).containsExactly("Web-Server");
        verify(repository, times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void addTagTrimsAndPersistsTheNormalizedValue() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.addTag("a1", "  containment  ");

        assertThat(service.get("a1").tags()).containsExactly("containment");
    }

    @Test
    void addTagRejectsBlankAndOversizedTags() {
        String tooLong = "x".repeat(65);

        assertThatThrownBy(() -> service.addTag("a1", "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("标签")
                .extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.addTag("a1", tooLong))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(400);

        verifyNoInteractions(repository);
    }

    @Test
    void addNoteIsSetOncePerIdempotencyKeyAcrossRetries() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition first =
                service.addNote("a1", "soar", "IOC enriched", "run-1:node-1");
        AlarmDispositionService.Disposition replay =
                service.addNote("a1", "soar", "IOC enriched", "run-1:node-1");

        assertThat(first.notes()).hasSize(1);
        assertThat(replay.notes()).hasSize(1);
        assertThat(service.get("a1").notes())
                .extracting(AlarmDispositionService.Disposition.Note::content)
                .containsExactly("IOC enriched");
        verify(repository, times(1)).save(any(DispositionEntity.class));
        assertThat(entity.getNoteKeys()).contains("run-1:node-1");
    }

    @Test
    void distinctKeysEachRecordTheirOwnNote() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.addNote("a1", "soar", "first", "key-1");
        service.addNote("a1", "soar", "second", "key-2");

        assertThat(service.get("a1").notes())
                .extracting(AlarmDispositionService.Disposition.Note::content)
                .containsExactly("first", "second");
        verify(repository, times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void addNoteDefaultsTheAuthorAndTrimsTheContent() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition result = service.addNote("a1", null, "  trimmed  ");

        assertThat(result.notes().getFirst().author()).isEqualTo("operator");
        assertThat(result.notes().getFirst().content()).isEqualTo("trimmed");
    }

    @Test
    void addNoteRejectsBlankContentAndOversizedKeys() {
        String tooLongKey = "k".repeat(256);

        assertThatThrownBy(() -> service.addNote("a1", "alice", "   "))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.addNote("a1", "alice", "note", tooLongKey))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotency-Key")
                .extracting("code").isEqualTo(400);

        verify(repository, never()).save(any(DispositionEntity.class));
    }

    @Test
    void unknownAlarmFallsBackToAnOpenDisposition() {
        given(repository.findByAlarmIdAndTenantId("missing", "tenant-a")).willReturn(Optional.empty());

        AlarmDispositionService.Disposition disposition = service.get("missing");

        assertThat(disposition.status()).isEqualTo("OPEN");
        assertThat(disposition.assignee()).isNull();
        assertThat(disposition.notes()).isEmpty();
        assertThat(disposition.tags()).isEmpty();
    }

    @Test
    void persistedStateIsScopedToTheCurrentTenant() {
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.empty());
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.empty());
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        service.addTag("a1", "prod");

        ArgumentCaptor<DispositionEntity> captor = ArgumentCaptor.forClass(DispositionEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(captor.getValue().getAlarmId()).isEqualTo("a1");
        verify(repository).findForUpdate("a1", "tenant-a");
        verify(repository).findByAlarmIdAndTenantId("a1", "tenant-a");
    }

    @Test
    void malformedTagsJsonIsToleratedAsAnEmptyTagList() {
        DispositionEntity entity = stored();
        entity.setStatus("RESOLVED");
        entity.setTags("not-json");
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));

        AlarmDispositionService.Disposition disposition = service.get("a1");

        assertThat(disposition.status()).isEqualTo("RESOLVED");
        assertThat(disposition.tags()).isEmpty();
    }

    @Test
    void tagRoundTripSurvivesJsonSerialization() {
        DispositionEntity entity = stored();
        entity.setTags("[\"a\",\"b\",\"a\"]");
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));

        assertThat(service.get("a1").tags()).containsExactly("a", "b");
    }

    @Test
    void getRejectsACallsWithoutTenantContext() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.get("a1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户");

        TenantContext.set("tenant-a");
    }

    @Test
    void setStatusKeepsNotesAndAssigneeWhileValidatingTheState() {
        DispositionEntity entity = stored();
        entity.setNotes("[{\"author\":\"alice\",\"content\":\"keep\",\"at\":\"2026-01-01T00:00:00Z\"}]");
        entity.setTags("[\"prod\"]");
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition result = service.setStatus("a1", "resolved");

        assertThat(result.status()).isEqualTo("RESOLVED");
        assertThat(result.assignee()).isEqualTo("alice");
        assertThat(result.notes()).hasSize(1);
        // setStatus predates tags and uses the compatibility constructor, so
        // the returned snapshot carries no tags (documented behaviour)
        assertThat(result.tags()).isEmpty();

        assertThatThrownBy(() -> service.setStatus("a1", "GARBAGE"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(400);
    }
}
