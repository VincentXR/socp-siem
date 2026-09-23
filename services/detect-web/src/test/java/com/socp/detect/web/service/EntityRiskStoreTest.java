package com.socp.detect.web.service;

import com.socp.detect.web.persistence.entity.EntityRiskAlertEntity;
import com.socp.detect.web.persistence.entity.EntityRiskProfileEntity;
import com.socp.detect.web.persistence.repository.EntityRiskAlertRepository;
import com.socp.detect.web.persistence.repository.EntityRiskProfileRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntityRiskStoreTest {

    private EntityRiskProfileRepository profiles;
    private EntityRiskAlertRepository appliedAlerts;
    private EntityRiskStore store;
    private EntityRiskCounterStore counters;

    @BeforeEach
    void setUp() {
        TenantContext.set("tenant-risk");
        Watchlists.clear();
        profiles = mock(EntityRiskProfileRepository.class);
        appliedAlerts = mock(EntityRiskAlertRepository.class);
        counters = mock(EntityRiskCounterStore.class);
        store = new EntityRiskStore(profiles, appliedAlerts, counters);
    }

    @AfterEach
    void tearDown() {
        Watchlists.clear();
        TenantContext.clear();
    }

    @Test
    void recordsAUniqueAlertAndAdvancesTheTenantProfile() {
        when(appliedAlerts.findByTenantIdAndAlertId("tenant-risk", "alert-1"))
                .thenReturn(Optional.empty());
        when(profiles.findForUpdate("tenant-risk", "admin")).thenReturn(Optional.empty());
        when(appliedAlerts.countByTenantIdAndEntityAndCreatedAtAfter(
                eq("tenant-risk"), eq("admin"), any(Instant.class))).thenReturn(1L);

        RiskScorer.Score score = store.recordForAlert(
                "alert-1", "admin", Severity.HIGH, "T1110", "RULE-1", "Privilege escalation", 2);

        assertThat(score.score()).isGreaterThan(0);
        ArgumentCaptor<EntityRiskProfileEntity> profile = ArgumentCaptor.forClass(EntityRiskProfileEntity.class);
        verify(profiles).saveAndFlush(profile.capture());
        assertThat(profile.getValue().getTenantId()).isEqualTo("tenant-risk");
        assertThat(profile.getValue().getEntity()).isEqualTo("admin");
        assertThat(profile.getValue().getAlerts()).isEqualTo(1);
        assertThat(profile.getValue().getMaxSeverity()).isEqualTo("HIGH");
        assertThat(profile.getValue().getMitreJson()).isEqualTo("{}");
        verify(counters).increment("tenant-risk", profile.getValue().getStorageId(), "MITRE", "T1110");
        assertThat(profile.getValue().getRulesJson()).isEqualTo("{}");
        verify(counters).increment("tenant-risk", profile.getValue().getStorageId(), "RULE", "Privilege escalation");

        ArgumentCaptor<EntityRiskAlertEntity> applied = ArgumentCaptor.forClass(EntityRiskAlertEntity.class);
        verify(appliedAlerts).save(applied.capture());
        assertThat(applied.getValue().getTenantId()).isEqualTo("tenant-risk");
        assertThat(applied.getValue().getAlertId()).isEqualTo("alert-1");
        assertThat(applied.getValue().getEntity()).isEqualTo("admin");
        assertThat(applied.getValue().getScore()).isEqualTo(score.score());
    }

    @Test
    void duplicateAlertReturnsTheDurableScoreWithoutMutatingTheProfile() {
        EntityRiskAlertEntity existing = new EntityRiskAlertEntity();
        existing.setAlertId("alert-duplicate");
        existing.setScore(72);
        existing.setLevel("HIGH");
        existing.setBreakdownJson("{\"severity\":45,\"intel\":16}");
        when(appliedAlerts.findByTenantIdAndAlertId("tenant-risk", "alert-duplicate"))
                .thenReturn(Optional.of(existing));

        RiskScorer.Score score = store.recordForAlert(
                "alert-duplicate", "admin", Severity.CRITICAL, "T1486", "RULE-2", "ignored", 3);

        assertThat(score.score()).isEqualTo(72);
        assertThat(score.level()).isEqualTo("HIGH");
        assertThat(score.breakdown()).containsEntry("severity", 45);
        verify(profiles, never()).findForUpdate(anyString(), anyString());
        verify(profiles, never()).save(any(EntityRiskProfileEntity.class));
        verify(appliedAlerts, never()).save(any(EntityRiskAlertEntity.class));
    }

    @Test
    void projectsTopSummaryAndCriticalWatchlistState() {
        Watchlists.putTemplate("crown_jewels", List.of("db-core"));
        EntityRiskProfileEntity high = profile("db-core", 90, 2, "CRITICAL");
        EntityRiskProfileEntity medium = profile("web-1", 45, 1, "MEDIUM");
        when(profiles.topAt(eq("tenant-risk"), anyLong(), anyInt())).thenReturn(List.of(high));
        var summary = mock(EntityRiskProfileRepository.RiskSummary.class);
        when(summary.getEntities()).thenReturn(2L);
        when(summary.getCritical()).thenReturn(1L);
        when(profiles.summarizeAt(eq("tenant-risk"), anyLong())).thenReturn(summary);
        when(profiles.findByTenantIdAndEntity("tenant-risk", "db-core"))
                .thenReturn(Optional.of(high));

        Map<String, Object> top = store.top(1).getFirst();
        assertThat(top).containsEntry("entity", "db-core")
                .containsEntry("critical", true)
                .containsEntry("alerts", 2L);
        assertThat((Map<String, Object>) store.get("db-core"))
                .containsEntry("level", "CRITICAL")
                .containsEntry("maxSeverity", "CRITICAL");
        assertThat((Map<String, Object>) store.summary()).containsEntry("entities", 2L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byLevel = (Map<String, Long>) store.summary().get("byLevel");
        assertThat(byLevel).containsEntry("CRITICAL", 1L);
    }

    @Test
    void requiresAnExplicitTenantForDurableProjection() {
        TenantContext.clear();

        assertThatThrownBy(() -> store.top(10))
                .isInstanceOf(IllegalStateException.class);
    }

    private static EntityRiskProfileEntity profile(String entity, double score,
                                                    long alerts, String severity) {
        EntityRiskProfileEntity profile = new EntityRiskProfileEntity();
        Instant now = Instant.now();
        profile.setTenantId("tenant-risk");
        profile.setEntity(entity);
        profile.setScore(score);
        profile.setScoreAt(now);
        profile.setAlerts(alerts);
        profile.setFirstSeen(now.minusSeconds(60));
        profile.setLastSeen(now);
        profile.setMaxSeverity(severity);
        profile.setMitreJson("{\"T1110\":2}");
        profile.setRulesJson("{\"Login rule\":2}");
        return profile;
    }
}
