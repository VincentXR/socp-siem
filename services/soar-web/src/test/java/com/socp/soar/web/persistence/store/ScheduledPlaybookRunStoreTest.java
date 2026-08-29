package com.socp.soar.web.persistence.store;

import com.socp.soar.web.persistence.entity.ScheduledPlaybookRunEntity;
import com.socp.soar.web.persistence.repository.ScheduledPlaybookRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduledPlaybookRunStoreTest {

    @Mock
    private ScheduledPlaybookRunRepository repository;

    @Test
    void claimUsesAStableMinuteKey() {
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
        ScheduledPlaybookRunStore store = new ScheduledPlaybookRunStore(repository);
        Instant fire = Instant.parse("2026-08-29T03:30:42Z");

        ScheduledPlaybookRunStore.Claim claim = store.claim("tenant-a", "pb-1", fire);

        assertNotNull(claim);
        assertEquals(Instant.parse("2026-08-29T03:30:00Z"), claim.scheduledFor());
        assertEquals(ScheduledPlaybookRunStore.claimId(
                "tenant-a", "pb-1", claim.scheduledFor()), claim.id());
        ArgumentCaptor<ScheduledPlaybookRunEntity> row =
                ArgumentCaptor.forClass(ScheduledPlaybookRunEntity.class);
        verify(repository).saveAndFlush(row.capture());
        assertEquals("PROCESSING", row.getValue().getStatus());
    }

    @Test
    void duplicateConstraintLossReturnsNoClaim() {
        given(repository.saveAndFlush(any())).willThrow(
                new DataIntegrityViolationException("duplicate"));
        ScheduledPlaybookRunStore store = new ScheduledPlaybookRunStore(repository);

        assertNull(store.claim("tenant-a", "pb-1", Instant.parse("2026-08-29T03:30:00Z")));
    }

    @Test
    void completionPersistsTheTerminalState() {
        ScheduledPlaybookRunEntity row = new ScheduledPlaybookRunEntity();
        row.setId("claim-1");
        row.setTenantId("tenant-a");
        given(repository.findByIdAndTenantId("claim-1", "tenant-a")).willReturn(Optional.of(row));
        ScheduledPlaybookRunStore store = new ScheduledPlaybookRunStore(repository);

        store.complete(new ScheduledPlaybookRunStore.Claim(
                "claim-1", "tenant-a", "pb-1", Instant.parse("2026-08-29T03:30:00Z")));

        assertEquals("COMPLETED", row.getStatus());
        assertNull(row.getLastError());
        verify(repository).save(row);
    }
}
