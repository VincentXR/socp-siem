package com.socp.alert.persistence.repository;

import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-level contract for the asynchronous enrichment field patch. */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AlarmEnrichmentRepositoryTest {

    @Autowired
    private AlarmRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void enrichmentPatchPreservesConcurrentAlarmFields() {
        TenantContext.set("tenant-a");
        Alarm alarm = new Alarm("R-1", "IOC", Severity.HIGH,
                "connection to 203.0.113.10", "203.0.113.10");
        alarm.setTenantId("tenant-a");
        alarm.setStatus("CLOSED");
        alarm = repository.saveAndFlush(alarm);

        assertThat(repository.updateEnrichment("tenant-a", alarm.getId(),
                "[{\"ioc\":\"203.0.113.10\"}]", 87, "CRITICAL")).isEqualTo(1);
        entityManager.clear();

        Alarm reloaded = repository.findByTenantIdAndId("tenant-a", alarm.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("CLOSED");
        assertThat(reloaded.getTiHits()).contains("203.0.113.10");
        assertThat(reloaded.getRiskScore()).isEqualTo(87);
        assertThat(reloaded.getRiskLevel()).isEqualTo("CRITICAL");
    }
}
