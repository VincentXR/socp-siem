package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmQuery;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.entity.DispositionEntity;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(showSql = false)
class AlarmInvestigationQueryTest {
    @Autowired AlarmRepository repository;
    @Autowired TestEntityManager entities;
    @AfterEach void clear() { TenantContext.clear(); }
    private Alarm alarm(String tenant, Severity severity, String entity, String technique, Instant at) {
        TenantContext.set(tenant);
        Alarm row = new Alarm("r-1", "Rule", severity, "evidence", entity);
        row.setTenantId(tenant); row.setSourceAlertId(UUID.randomUUID().toString());
        row.setMitre(technique); row.setOccurredAt(at); entities.persist(row); return row;
    }
    private AlarmQuery criteria(String owner, String status, String entity, String technique, String group) {
        return new AlarmQuery(null, null, status, null, AlarmQuery.SortField.OCCURRED_AT, false)
                .withInvestigation(owner, entity, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"), technique, group);
    }
    @Test void highRiskPivotIncludesCriticalAndPreservesTenantExactEntityTechniqueAndHalfOpenWindow() {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Alarm high = alarm("a", Severity.HIGH, "host-1", "T1110", from);
        Alarm critical = alarm("a", Severity.CRITICAL, "host-1", "T1110", from.plusSeconds(5));
        alarm("a", Severity.MEDIUM, "host-1", "T1110", from);
        alarm("b", Severity.CRITICAL, "host-1", "T1110", from);
        alarm("a", Severity.HIGH, "host-10", "T1110", from);
        alarm("a", Severity.HIGH, "host-1", "T1110.001", from);
        alarm("a", Severity.HIGH, "host-1", "T1110", from.plusSeconds(86400));
        entities.flush(); entities.clear(); TenantContext.set("a");
        var page = repository.page("a", criteria(null, null, "host-1", "T1110", "high"), PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(Alarm::getId).containsExactly(critical.getId(), high.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
    }
    @Test void ownershipAndDisplayedStatusComeFromTheSameTenantDisposition() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        Alarm assigned = alarm("a", Severity.HIGH, "host", "T1110", now);
        Alarm unassigned = alarm("a", Severity.HIGH, "host", "T1110", now);
        DispositionEntity state = new DispositionEntity(); state.setTenantId("a"); state.setAlarmId(assigned.getId());
        state.setAssignee("alice"); state.setStatus("INVESTIGATING"); entities.persist(state);
        entities.flush(); entities.clear(); TenantContext.set("a");
        var mine = repository.page("a", criteria("alice", "INVESTIGATING", "host", null, null), PageRequest.of(0, 20));
        assertThat(mine.getContent()).singleElement().satisfies(row -> {
            assertThat(row.getId()).isEqualTo(assigned.getId()); assertThat(row.getAssignee()).isEqualTo("alice");
            assertThat(row.getStatus()).isEqualTo("INVESTIGATING");
        });
        assertThat(repository.page("a", criteria("unassigned", "OPEN", "host", null, null), PageRequest.of(0, 20)).getContent())
                .extracting(Alarm::getId).containsExactly(unassigned.getId());
    }
    @Test void statusSortUsesTheDisplayedDispositionOnEveryDatabasePage() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        Alarm changed = alarm("a", Severity.HIGH, "host", "T1110", now);
        Alarm unchanged = alarm("a", Severity.HIGH, "host", "T1110", now);
        DispositionEntity state = new DispositionEntity(); state.setTenantId("a"); state.setAlarmId(changed.getId());
        state.setStatus("CLOSED"); entities.persist(state); entities.flush(); entities.clear();
        var query = new AlarmQuery(null, null, null, null, AlarmQuery.SortField.STATUS, true);
        assertThat(repository.page("a", query, PageRequest.of(0, 1)).getContent()).extracting(Alarm::getId).containsExactly(changed.getId());
        assertThat(repository.page("a", query, PageRequest.of(1, 1)).getContent()).extracting(Alarm::getId).containsExactly(unchanged.getId());
    }
    @Test void legacyNullDispositionStatusDoesNotHideAnOpenAlarmFromItsFilter() {
        Alarm legacy = alarm("a", Severity.HIGH, "host", "T1110", Instant.parse("2026-10-01T12:00:00Z"));
        DispositionEntity state = new DispositionEntity(); state.setTenantId("a"); state.setAlarmId(legacy.getId());
        entities.persist(state); entities.flush(); entities.clear();
        assertThat(repository.page("a", criteria("unassigned", "OPEN", "host", null, null), PageRequest.of(0, 20)).getContent())
                .extracting(Alarm::getId).containsExactly(legacy.getId());
    }
    @Test void activeQueueUsesEffectiveStatusAndComposesExactAssigneeWithInvestigationFilters() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        Alarm investigating = alarm("a", Severity.CRITICAL, "host", "T1110", now);
        investigating.setStatus("CLOSED");
        DispositionEntity active = new DispositionEntity(); active.setTenantId("a"); active.setAlarmId(investigating.getId());
        active.setAssignee("alice"); active.setStatus("INVESTIGATING"); entities.persist(active);
        Alarm resolved = alarm("a", Severity.HIGH, "host", "T1110", now);
        DispositionEntity closed = new DispositionEntity(); closed.setTenantId("a"); closed.setAlarmId(resolved.getId());
        closed.setAssignee("alice"); closed.setStatus("CLOSED"); entities.persist(closed);
        entities.flush(); entities.clear(); TenantContext.set("a");
        var query = criteria("alice", "ACTIVE", "host", "T1110", "high").withAssignee(" alice ");
        assertThat(repository.page("a", query, PageRequest.of(0, 1)).getContent())
                .extracting(Alarm::getId).containsExactly(investigating.getId());
        assertThat(repository.count("a", query)).isEqualTo(1);
        assertThat(repository.count("a", query.withAssignee("bob"))).isZero();
        assertThat(repository.count("a", criteria("unassigned", "ACTIVE", "host", "T1110", "high").withAssignee("alice"))).isZero();
    }

    @Test void invalidWindowOrSeverityGroupCannotBecomeABroadQuery() {
        assertThatThrownBy(() -> criteria(null, null, null, null, "critical")).hasMessageContaining("severityGroup");
        assertThatThrownBy(() -> criteria(null, null, null, "T1110 OR 1=1", null)).hasMessageContaining("technique");
    }
}
