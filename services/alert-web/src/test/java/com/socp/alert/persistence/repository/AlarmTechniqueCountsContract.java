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
