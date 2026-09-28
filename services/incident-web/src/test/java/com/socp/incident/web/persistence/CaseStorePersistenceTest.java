package com.socp.incident.web.persistence;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.incident.web.persistence.repository.AlarmCaseLinkRepository;
import com.socp.incident.web.persistence.repository.CaseRuleLinkRepository;
import com.socp.incident.web.persistence.repository.CaseTimelineRepository;
import com.socp.incident.web.persistence.repository.CaseRepository;
import jakarta.persistence.EntityManagerFactory;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Hermetic H2 coverage for the tenant-scoped persistence contract. */
@DataJpaTest
@Import(CaseStore.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CaseStorePersistenceTest {

    @Autowired
    private CaseStore store;

    @Autowired private CaseTimelineRepository timelineRepository;
    @Autowired private AlarmCaseLinkRepository alarmLinkRepository;
    @Autowired private CaseRuleLinkRepository ruleLinkRepository;
    @Autowired private CaseRepository caseRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void timelinePreviewIsDatabaseBoundedAndPagesHaveStableTimestampTies() {
        TenantContext.set("bounded-timeline-tenant");
        CaseTimelineReadAssertions.verify(store, timelineRepository, entityManagerFactory);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void persistsCasesAndKeepsReadsInsideTheCurrentTenant() {
        TenantContext.set("tenant-a");
        Case tenantACase = Case.create("suspicious login", "host-1", "HIGH");
        store.save(tenantACase);

        assertThat(store.list()).extracting(Case::id).containsExactly(tenantACase.id());
        assertThat(store.get(tenantACase.id())).isNotNull();
        assertThat(store.openCaseId("host-1")).isEqualTo(tenantACase.id());
        assertThat(store.count()).isEqualTo(1L);
        assertThat(store.countByStatusIn(List.of("OPEN", "INVESTIGATING"))).isEqualTo(1L);
        assertThat(store.page(1, 10, "suspicious", "OPEN").getContent())
                .extracting(Case::id).containsExactly(tenantACase.id());

        TenantContext.set("tenant-b");
        assertThat(store.list()).isEmpty();
        assertThat(store.get(tenantACase.id())).isNull();
        assertThat(store.openCaseId("host-1")).isNull();
    }

    @Test
    void timelineAppendIsIdempotentAndPaged() {
        TenantContext.set("timeline-tenant");
        Case incident = Case.create("timeline", "host-2", "MEDIUM");
        store.save(incident);
        TimelineEvent first = new TimelineEvent(Instant.parse("2026-08-28T01:00:00Z"),
                "NOTE", "first", "test", null, "note-1");
        TimelineEvent second = new TimelineEvent(Instant.parse("2026-08-28T01:01:00Z"),
                "NOTE", "second", "test", null, "note-2");

        assertThat(store.appendTimeline(incident.id(), first)).isTrue();
        assertThat(store.appendTimeline(incident.id(), first)).isFalse();
        assertThat(store.appendTimeline(incident.id(), second)).isTrue();
        assertThat(store.appendTimeline("missing", second)).isFalse();
        assertThat(store.timeline(incident.id(), 0, 1).getTotalElements()).isEqualTo(2);
        assertThat(store.timeline(incident.id(), 0, 1).getContent()).hasSize(1);
        assertThat(store.get(incident.id()).timeline())
                .extracting(TimelineEvent::idempotencyKey)
                .containsExactly("note-1", "note-2");
    }

    @Test
    void persistsNormalizedAlarmAndRuleAssociationsWithoutDuplicatingThem() {
        TenantContext.set("association-tenant");
        TimelineEvent event = new TimelineEvent(Instant.parse("2026-09-28T00:00:00Z"),
                "ALARM", "detected", "detection", "alarm-z");
        Case incident = Case.create("normalized links", "host-z", "HIGH")
                .withAdded("rule-z", "alarm-z", event);

        store.save(incident);
        store.save(incident);

        assertThat(alarmLinkRepository.findByTenantIdAndCaseIdOrderByAlarmIdAsc(
                "association-tenant", incident.id()))
                .extracting(link -> link.getAlarmId())
                .containsExactly("alarm-z");
        assertThat(ruleLinkRepository.findByTenantIdAndCaseIdOrderByRuleIdAsc(
                "association-tenant", incident.id()))
                .extracting(link -> link.getRuleId())
                .containsExactly("rule-z");
        assertThat(store.get(incident.id()).alarmIds()).containsExactly("alarm-z");
        assertThat(store.get(incident.id()).ruleIds()).containsExactly("rule-z");
        Case summary = store.page(1, 10, "normalized", "OPEN").getContent().getFirst();
        assertThat(summary.alarmIds()).isEmpty();
        assertThat(summary.alarmCount()).isEqualTo(1);
        assertThat(store.alarms(incident.id(), 0, 10).getContent()).containsExactly("alarm-z");
    }

    @Test
    void incrementalAssociationsRemainMergedWithLegacyJsonUntilBackfill() {
        TenantContext.set("legacy-association-tenant");
        Case incident = Case.create("legacy links", "host-legacy", "LOW");
        store.save(incident);
        var row = caseRepository.findByTenantIdAndId("legacy-association-tenant", incident.id())
                .orElseThrow();
        row.setAlarmIdsJson("[\"alarm-old\"]");
        row.setRuleIdsJson("[\"rule-old\"]");
        caseRepository.saveAndFlush(row);

        TimelineEvent event = new TimelineEvent(Instant.parse("2026-09-28T01:00:00Z"),
                "ALARM", "new", "detection", "alarm-new");
        store.saveAlarmDelta(incident.withAdded("rule-new", "alarm-new", event, "CRITICAL"),
                "rule-new", "alarm-new", event);

        assertThat(store.get(incident.id()).alarmIds())
                .containsExactly("alarm-new", "alarm-old");
        assertThat(store.get(incident.id()).ruleIds())
                .containsExactly("rule-new", "rule-old");
        assertThat(store.alarms(incident.id(), 0, 10).getContent())
                .containsExactly("alarm-new", "alarm-old");
        assertThat(store.rules(incident.id(), 0, 10).getContent())
                .containsExactly("rule-new", "rule-old");
        Case summary = store.page(1, 10, "legacy", "OPEN").getContent().getFirst();
        assertThat(summary.alarmCount()).isEqualTo(2);
        assertThat(summary.ruleCount()).isEqualTo(2);
    }
}
