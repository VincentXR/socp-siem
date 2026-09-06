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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Branch coverage for the disposition note/tag paths: the three-arg note
 * delegate, tag validation guards, case-insensitive tag de-duplication and
 * the tolerant readers for malformed persisted JSON columns.
 */
@ExtendWith(MockitoExtension.class)
class AlarmDispositionEdgeCoverageTest {

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
    void threeArgAddNoteDelegatesAndAppendsTheNote() {
        DispositionEntity entity = stored();
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition result = service.addNote("a1", "alice", "hello");

        assertThat(result.status()).isEqualTo("OPEN");
        assertThat(result.notes()).hasSize(1);
        assertThat(result.notes().getFirst().author()).isEqualTo("alice");
        assertThat(result.notes().getFirst().content()).isEqualTo("hello");
        verify(repository).save(any(DispositionEntity.class));
    }

    @Test
    void addTagRejectsNullBlankAndOversizedTagsBeforeTouchingTheRepository() {
        assertThatThrownBy(() -> service.addTag("a1", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(400);
        assertThatThrownBy(() -> service.addTag("a1", "   "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("标签");
        assertThatThrownBy(() -> service.addTag("a1", "x".repeat(65)))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(400);

        verifyNoInteractions(repository);
    }

    @Test
    void addTagTrimsAndSkipsDuplicateTagsCaseInsensitively() {
        DispositionEntity entity = stored();
        entity.setTags("[\"Prod\"]");
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition duplicate = service.addTag("a1", "  prod  ");
        AlarmDispositionService.Disposition added = service.addTag("a1", "Containment");

        assertThat(duplicate.tags()).containsExactly("Prod");
        assertThat(added.tags()).containsExactly("Prod", "Containment");
        verify(repository, times(2)).save(any(DispositionEntity.class));
    }

    @Test
    void malformedTagsJsonDegradesToAnEmptyTagList() {
        DispositionEntity entity = stored();
        entity.setTags("not-json");
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));

        assertThat(service.get("a1").tags()).isEmpty();
    }

    @Test
    void tagRoundTripFiltersBlankEntriesAndTrimsValues() {
        DispositionEntity entity = stored();
        entity.setTags("[\"a\", \"\", \"  b  \"]");
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));

        assertThat(service.get("a1").tags()).containsExactly("a", "b");
    }

    @Test
    void malformedNoteKeysJsonStillRecordsNewIdempotentNotes() {
        DispositionEntity entity = stored();
        entity.setNoteKeys("not-json");
        given(repository.findForUpdate("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.findByAlarmIdAndTenantId("a1", "tenant-a")).willReturn(Optional.of(entity));
        given(repository.save(any(DispositionEntity.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AlarmDispositionService.Disposition result = service.addNote("a1", "soar", "enriched", "key-1");

        assertThat(result.notes()).hasSize(1);
        assertThat(result.notes().getFirst().content()).isEqualTo("enriched");
        assertThat(entity.getNoteKeys()).isEqualTo("[\"key-1\"]");
        verify(repository).save(any(DispositionEntity.class));
    }
}
