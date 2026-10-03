package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.service.AlarmStatisticsService;
import com.socp.platform.tenant.context.TenantContext;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
abstract class AlarmTechniqueCountsContract {
    @Autowired AlarmRepository repository;
    @Autowired TestEntityManager entityManager;

    @AfterEach void clearTenant() { TenantContext.clear(); }

    private void alarm(String tenant, String technique, Instant at) {
        TenantContext.set(tenant);
        Alarm alarm = new Alarm("R1", "Rule", Severity.HIGH, "message", "entity");
        alarm.setTenantId(tenant);
        alarm.setSourceAlertId(UUID.randomUUID().toString());
        alarm.setMitre(technique);
        alarm.setOccurredAt(at);
        entityManager.persist(alarm);
    }

    @Test
    void ownerAndTimeFiltersApplyBeforePagingAndCountingWithoutCrossTenantDispositionMatches() {
        Instant at = Instant.parse("2026-09-01T12:00:00Z");
        TenantContext.set("tenant-a");
        Alarm mine = new Alarm("R1", "Rule", Severity.HIGH, "message", "entity");
        mine.setTenantId("tenant-a"); mine.setSourceAlertId(UUID.randomUUID().toString()); mine.setOccurredAt(at); mine.setStatus("OPEN");
        entityManager.persistAndFlush(mine);
        var wrongTenant = new com.socp.alert.persistence.entity.DispositionEntity();
        wrongTenant.setTenantId("tenant-b"); wrongTenant.setAlarmId(mine.getId()); wrongTenant.setAssignee("alice");
        entityManager.persistAndFlush(wrongTenant);
        var query = new com.socp.alert.domain.AlarmQuery(null, null, "ACTIVE", null,
                com.socp.alert.domain.AlarmQuery.SortField.OCCURRED_AT, false).withScope("alice", at, at);
        assertThat(repository.count("tenant-a", query)).isZero();
        var disposition = new com.socp.alert.persistence.entity.DispositionEntity();
        disposition.setTenantId("tenant-a"); disposition.setAlarmId(mine.getId()); disposition.setAssignee("alice");
        entityManager.persistAndFlush(disposition);
        assertThat(repository.page("tenant-a", query, org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent()).extracting(Alarm::getId).containsExactly(mine.getId());
        assertThat(repository.count("tenant-a", query)).isEqualTo(1);
        assertThat(repository.count("tenant-a", query.withScope("bob", at, at))).isZero();
        assertThat(repository.count("tenant-a", query.withScope("alice", at.plusSeconds(1), null))).isZero();
        mine.setStatus("CLOSED"); entityManager.flush();
        assertThat(repository.count("tenant-a", query)).isZero();
    }

    @Test
    void countsBeyondOverviewSampleWithoutHydratingEntitiesOrCrossingTenantAndWindow() {
        Instant now = Instant.now();
        for (int i = 0; i < 151; i++) alarm("tenant-a", "T1110", now.minusSeconds(60));
        alarm("tenant-a", "T1110", now.minus(Duration.ofDays(8)));
        alarm("tenant-a", "T1110", now.plusSeconds(60));
        alarm("tenant-a2", "T1110", now.minusSeconds(60));
        alarm("tenant-a", "T9999", now.minusSeconds(60));
        alarm("tenant-a", null, now.minusSeconds(60));
        entityManager.flush(); entityManager.clear();
        var statistics = entityManager.getEntityManager().getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        TenantContext.set("tenant-a");
        var result = new AlarmStatisticsService(repository).techniqueCounts(List.of("T1110", "T1059.001", "T1110"));
        assertThat(result.counts()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of("T1110", 151L, "T1059.001", 0L));
        assertThat(Duration.between(Instant.parse(result.from()), Instant.parse(result.until()))).isEqualTo(Duration.ofDays(7));
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void queryIncludesLowerBoundaryAndExcludesUpperBoundary() {
        Instant until = Instant.parse("2026-09-23T00:00:00Z"), since = until.minus(Duration.ofDays(7));
        alarm("tenant-a", "T1110", since);
        alarm("tenant-a", "T1110", since.minusSeconds(1));
        alarm("tenant-a", "T1110", until);
        entityManager.flush(); entityManager.clear();
        var result = repository.countByTechniqueInWindow("tenant-a", List.of("T1110"), since, until);
        assertThat(result).singleElement().satisfies(count -> assertThat(count.count()).isEqualTo(1));
    }
}
