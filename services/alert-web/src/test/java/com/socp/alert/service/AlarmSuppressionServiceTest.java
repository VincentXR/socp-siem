package com.socp.alert.service;

import com.socp.alert.persistence.entity.AlarmSuppressionEntity;
import com.socp.alert.persistence.repository.AlarmSuppressionRepository;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Suppression windows are the durable half of noise reduction: the decision has
 * to be identical on every replica and survive a restart.
 */
@ExtendWith(MockitoExtension.class)
class AlarmSuppressionServiceTest {

    @Mock
    private AlarmSuppressionRepository repository;

    private AlarmSuppressionService service;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-a");
        service = new AlarmSuppressionService(repository, org.mockito.Mockito.mock(AlarmSuppressionLock.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AlarmSuppressionEntity window(String ruleId, String entityKey, Instant expiresAt) {
        AlarmSuppressionEntity entity = new AlarmSuppressionEntity();
        entity.setTenantId("tenant-a");
        entity.setRuleId(ruleId);
        entity.setEntityKey(entityKey);
        entity.setOrigin(AlarmSuppressionService.ORIGIN_FALSE_POSITIVE);
        entity.setReason("known scanner");
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    @Test
    void matchesExactEntityAndTheRuleWideScopeInOneLookup() {
        when(repository.findActive(eq("tenant-a"), eq("rule-1"),
                eq(List.of("10.0.0.5", "")), any(Instant.class)))
                .thenReturn(List.of(window("rule-1", "10.0.0.5", Instant.now().plusSeconds(600))));

        assertThat(service.suppresses("tenant-a", "rule-1", "10.0.0.5")).isTrue();
    }

    @Test
    void treatsMissingRuleIdAsNoSuppressionAndQueriesNothing() {
        assertThat(service.suppresses("tenant-a", " ", "10.0.0.5")).isFalse();
        verify(repository, never()).findActive(any(), any(), anyCollection(), any());
    }

    @Test
    void ignoresWindowsPastTheirExpiry() {
        when(repository.findActive(any(), any(), anyCollection(), any(Instant.class)))
                .thenReturn(List.of());
        assertThat(service.suppresses("tenant-a", "rule-1", "10.0.0.5")).isFalse();
    }

    @Test
    void reConfirmingTheSameScopeExtendsTheExistingWindowInsteadOfDuplicating() {
        AlarmSuppressionEntity existing = window("rule-1", "10.0.0.5", Instant.now().plusSeconds(60));
        existing.setId("sup-1");
        when(repository.findByTenantIdAndRuleIdAndEntityKey("tenant-a", "rule-1", "10.0.0.5"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(AlarmSuppressionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.record("rule-1", "10.0.0.5", "manual", "recurring scanner",
                "alarm-9", "analyst", 7_200L, false);

        ArgumentCaptor<AlarmSuppressionEntity> saved = ArgumentCaptor.forClass(AlarmSuppressionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("sup-1");
        assertThat(saved.getValue().getOrigin()).isEqualTo("MANUAL");
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now().plusSeconds(3_600));
        assertThat(result).containsEntry("ruleId", "rule-1").containsEntry("alarmId", "alarm-9");
    }

    @Test
    void blankEntityBecomesTheRuleWideScopeKey() {
        when(repository.findByTenantIdAndRuleIdAndEntityKey("tenant-a", "rule-1", ""))
                .thenReturn(Optional.empty());
        when(repository.save(any(AlarmSuppressionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.record("rule-1", null, "FALSE_POSITIVE", "noisy rule", null, "analyst", null, true);

        ArgumentCaptor<AlarmSuppressionEntity> saved = ArgumentCaptor.forClass(AlarmSuppressionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEntityKey()).isEmpty();
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
    }

    @Test
    void rejectsUnknownOriginAndOutOfRangeWindows() {
        assertThatThrownBy(() -> service.record("rule-1", null, "MUTED", "x", null, null, null, true))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.record("rule-1", null, "MANUAL", "x", null, null, 0L, true))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.record("rule-1", null, "MANUAL", "x", null, null, 31L * 86_400, true))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.record(" ", null, "MANUAL", "x", null, null, null, true))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void boundsTenantControlledWindowCardinality() {
        when(repository.countByTenantId("tenant-a")).thenReturn(1_000L);
        assertThatThrownBy(() -> service.record("rule-1", null, "MANUAL", "x", null, null, null, true))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(409));
    }

    @Test
    void missingEntityRequiresExplicitRuleWideConsent() {
        assertThatThrownBy(() -> service.record("rule", null, "MANUAL", "reason", null, "analyst", 3600L, false))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        verify(repository, never()).save(any());
    }

    @Test
    void renewalAtCapacitySucceedsAndNeverShortensTheWindow() {
        var existing = window("rule", "host", Instant.now().plusSeconds(7200));
        when(repository.findByTenantIdAndRuleIdAndEntityKey("tenant-a", "rule", "host"))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        Instant expiry = existing.getExpiresAt();
        assertThat(service.record("rule", "host", "MANUAL", "reason", null, "analyst", 3600L, false))
                .containsEntry("expiresAt", expiry);
        verify(repository, never()).countByTenantId(any());
    }

    @Test
    void listHidesExpiredWindows() {
        when(repository.findByTenantIdOrderByExpiresAtDesc("tenant-a")).thenReturn(List.of(
                window("rule-1", "10.0.0.5", Instant.now().plusSeconds(600)),
                window("rule-2", "", Instant.now().minusSeconds(600))));
        assertThat(service.list()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("ruleId", "rule-1"));
    }

    @Test
    void releaseDeletesTheExactScope() {
        service.release("rule-1", "10.0.0.5");
        verify(repository).deleteByTenantIdAndRuleIdAndEntityKey("tenant-a", "rule-1", "10.0.0.5");
    }
}
