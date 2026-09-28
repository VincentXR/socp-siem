package com.socp.search.config.service;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.persistence.entity.IngestParseFailureEntity;
import com.socp.search.config.persistence.repository.IngestParseFailureRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IngestParseFailureServiceTest {
    @Mock IngestParseFailureRepository repository;
    @Mock IngestEventNormalizer normalizer;
    @Mock IngestionCommitService commits;
    @Mock JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        given(jdbc.execute(any(ConnectionCallback.class))).willReturn("H2");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameRequestIdentityReturnsTheDurableRowWithoutConsumingCapacityAgain() {
        IngestParseFailureEntity existing = row("failure-1", "PENDING");
        given(repository.findByTenantIdAndFailureKey(eq("tenant-a"), any()))
                .willReturn(Optional.of(existing));

        IngestParseFailureEntity result = service(10).record(
                "raw", "collector-a", "rules:7", "bad payload", "request:1");

        assertThat(result).isSameAs(existing);
        verify(repository, never()).countByTenantId(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void tenantCapacityFailsBeforeAcknowledgementCanBeClaimed() {
        given(repository.findByTenantIdAndFailureKey(eq("tenant-a"), any()))
                .willReturn(Optional.empty());
        given(repository.countByTenantId("tenant-a")).willReturn(1L);

        assertThatThrownBy(() -> service(1).record(
                "raw", "collector-a", "rules:7", "bad payload", null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", 507);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void newFailurePersistsBoundedEvidenceWithAStableRequestIdentity() {
        given(repository.findByTenantIdAndFailureKey(eq("tenant-a"), any()))
                .willReturn(Optional.empty());
        given(repository.countByTenantId("tenant-a")).willReturn(0L);
        given(repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        IngestParseFailureEntity result = service(10).record(
                "raw-payload", " ", null, " ", "batch-7:line-3");

        assertThat(result.getId()).isNotBlank();
        assertThat(result.getFailureKey()).hasSize(64);
        assertThat(result.getCollectorId()).isEqualTo("unknown");
        assertThat(result.getParserVersion()).isEqualTo("unknown");
        assertThat(result.getFailureReason()).isEqualTo("parse failed");
        assertThat(result.getRawPayload()).isEqualTo("raw-payload");
        assertThat(result.getReceivedAt()).isNotNull();
        assertThat(result.getReplayStatus()).isEqualTo("PENDING");
        assertThat(result.getReplayAttempts()).isZero();
        verify(repository).saveAndFlush(result);
    }

    @Test
    void pageUsesTenantScopeAndOneBasedOperatorPagination() {
        IngestParseFailureEntity failure = row("failure-1", "PENDING");
        PageRequest request = PageRequest.of(1, 25);
        given(repository.findByTenantIdOrderByReceivedAtDescIdAsc("tenant-a", request))
                .willReturn(new PageImpl<>(List.of(failure), request, 50));

        var result = service(10).page(2, 25);

        assertThat(result.getTotalElements()).isEqualTo(50);
        assertThat(result.getContent()).singleElement().satisfies(item ->
                assertThat(item)
                        .containsEntry("id", "failure-1")
                        .containsEntry("rawPayload", "raw"));
    }

    @Test
    void replayUsesTheLockedRowAndPersistsOneStableEventIdentity() {
        IngestParseFailureEntity pending = row("failure-1", "PENDING");
        pending.setRawPayload("raw");
        pending.setCollectorId("collector-a");
        SearchEvent event = new SearchEvent("event-1", Instant.EPOCH, "auth", "host", "HIGH",
                "raw", Map.of("tenant_id", "tenant-a"), Map.of());
        given(repository.findForReplay("failure-1", "tenant-a")).willReturn(Optional.of(pending));
        given(normalizer.normalize(eq("raw"), eq("collector-a"), eq("quarantine:failure-1"), any()))
                .willReturn(new IngestEventNormalizer.NormalizedEvent(event, Map.of(), "collector-a"));
        given(commits.commit(List.of(event)))
                .willReturn(new IngestionCommitService.CommitResult(1, 1, 0, 1));

        Map<String, Object> result = service(10).replay("failure-1");

        assertThat(result).containsEntry("replayStatus", "REPLAYED")
                .containsEntry("created", 1);
        assertThat(pending.getReplayAttempts()).isEqualTo(1);
        assertThat(pending.getReplayedEventId()).isEqualTo("event-1");
        verify(repository).findForReplay("failure-1", "tenant-a");
        verify(commits).commit(anyList());
    }

    @Test
    void alreadyReplayedRowIsIdempotentAndDoesNotPublishAgain() {
        IngestParseFailureEntity replayed = row("failure-1", "REPLAYED");
        given(repository.findForReplay("failure-1", "tenant-a")).willReturn(Optional.of(replayed));

        Map<String, Object> result = service(10).replay("failure-1");

        assertThat(result).containsEntry("duplicate", true);
        verify(commits, never()).commit(anyList());
        verify(normalizer, never()).normalize(any(), any(), any(), any());
    }

    @Test
    void stillInvalidReplayRemainsPendingAndRecordsDiagnostic() {
        IngestParseFailureEntity pending = row("failure-1", "PENDING");
        given(repository.findForReplay("failure-1", "tenant-a")).willReturn(Optional.of(pending));
        given(normalizer.normalize(eq("raw"), eq("collector-a"), eq("quarantine:failure-1"), any()))
                .willThrow(new IngestParseException("still invalid under rules:8"));

        Map<String, Object> result = service(10).replay("failure-1");

        assertThat(result).containsEntry("replayStatus", "PENDING")
                .containsEntry("replayed", false)
                .containsEntry("lastError", "still invalid under rules:8");
        assertThat(pending.getReplayAttempts()).isEqualTo(1);
        verify(repository).save(pending);
        verify(commits, never()).commit(anyList());
    }

    @Test
    void replayDependencyFailureReturnsRetryableErrorWithoutMarkingSuccess() {
        IngestParseFailureEntity pending = row("failure-1", "PENDING");
        SearchEvent event = new SearchEvent("event-1", Instant.EPOCH, "auth", "host", "HIGH",
                "raw", Map.of("tenant_id", "tenant-a"), Map.of());
        given(repository.findForReplay("failure-1", "tenant-a")).willReturn(Optional.of(pending));
        given(normalizer.normalize(eq("raw"), eq("collector-a"), eq("quarantine:failure-1"), any()))
                .willReturn(new IngestEventNormalizer.NormalizedEvent(event, Map.of(), "collector-a"));
        given(commits.commit(List.of(event))).willThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service(10).replay("failure-1"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", 503);
        assertThat(pending.getReplayStatus()).isEqualTo("PENDING");
        assertThat(pending.getReplayedEventId()).isNull();
        verify(repository, never()).save(any());
    }

    private IngestParseFailureService service(int maxRows) {
        IngestRuntimeProperties properties = new IngestRuntimeProperties();
        properties.getQuarantine().setMaxRowsPerTenant(maxRows);
        return new IngestParseFailureService(repository, normalizer, commits, jdbc, properties);
    }

    private static IngestParseFailureEntity row(String id, String status) {
        IngestParseFailureEntity row = new IngestParseFailureEntity();
        row.setId(id);
        row.setCollectorId("collector-a");
        row.setRawPayload("raw");
        row.setReceivedAt(Instant.EPOCH);
        row.setParserVersion("rules:7");
        row.setFailureReason("bad payload");
        row.setReplayStatus(status);
        return row;
    }
}
