package com.socp.search.config.service;

import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestionOutboxAdminServiceTest {

    @Mock
    private IngestionOutboxRepository repository;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void listsOnlyCurrentTenantDeadRowsWithoutPayload() {
        TenantContext.set("tenant-a");
        IngestionOutboxEvent event = mock(IngestionOutboxEvent.class);
        given(event.getId()).willReturn("outbox-1");
        given(event.getEventId()).willReturn("event-1");
        given(event.getAttempts()).willReturn(12);
        given(repository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc("tenant-a", "DEAD"))
                .willReturn(List.of(event));

        var records = service().dead();

        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.id()).isEqualTo("outbox-1");
            assertThat(record.reference()).isEqualTo("event-1");
        });
    }

    @Test
    void requeuesWithinTenantAndResetsToPending() {
        TenantContext.set("tenant-a");
        IngestionOutboxEvent event = mock(IngestionOutboxEvent.class);
        given(repository.findByIdAndTenantId("outbox-1", "tenant-a"))
                .willReturn(Optional.of(event));
        given(repository.requeueDead(eq("outbox-1"), eq("tenant-a"), any(Instant.class)))
                .willReturn(1);

        var result = service().requeue("outbox-1");

        assertThat(result.status()).isEqualTo("PENDING");
        verify(repository).requeueDead(eq("outbox-1"), eq("tenant-a"), any(Instant.class));
    }

    @Test
    void discardRetainsThePreviousFailure() {
        TenantContext.set("tenant-a");
        IngestionOutboxEvent event = mock(IngestionOutboxEvent.class);
        given(event.getLastError()).willReturn("broker timeout");
        given(repository.findByIdAndTenantId("outbox-1", "tenant-a"))
                .willReturn(Optional.of(event));
        given(repository.discardDead(eq("outbox-1"), eq("tenant-a"),
                eq("operator discard: duplicate | previous failure: broker timeout"),
                any(Instant.class))).willReturn(1);

        var result = service().discard("outbox-1", "duplicate");

        assertThat(result.status()).isEqualTo("DISCARDED");
    }

    private IngestionOutboxAdminService service() {
        return new IngestionOutboxAdminService(repository, new SimpleMeterRegistry());
    }
}
