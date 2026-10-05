package com.socp.alert.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.alert.config.AlertDeliveryProperties;
import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.Severity;
import com.socp.alert.persistence.repository.AlarmRepository;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Import({AlarmEnrichmentCommitter.class, AlarmDeliveryRegistrar.class, AlarmEnrichmentCommitterPersistenceTest.Config.class})
@EnableConfigurationProperties(AlertDeliveryProperties.class)
@TestPropertySource(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "socp.alert.delivery.destinations=SOAR"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlarmEnrichmentCommitterPersistenceTest {
    @org.springframework.test.context.DynamicPropertySource
    static void nativePostgres(org.springframework.test.context.DynamicPropertyRegistry properties) {
        String url = System.getenv("SOCP_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/socp_downstream_[a-z]+"))
            throw new IllegalArgumentException("Use a dedicated loopback socp_downstream_* test database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("SOCP_TEST_JDBC_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("SOCP_TEST_JDBC_PASSWORD", ""));
        properties.add("spring.test.database.replace", () -> "NONE");
        properties.add("spring.flyway.enabled", () -> "true");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
    @Autowired AlarmRepository alarms;
    @Autowired AlarmDeliveryRepository deliveries;
    @Autowired AlarmEnrichmentCommitter committer;
    @MockitoSpyBean AlarmDeliveryRegistrar registrar;
    @TestConfiguration static class Config { @Bean ObjectMapper json() { return new ObjectMapper().findAndRegisterModules(); } }
    @BeforeEach void setup() { TenantContext.set("enrichment-" + java.util.UUID.randomUUID()); }
    @AfterEach void clear() { TenantContext.clear(); }
    Alarm create() {
        Alarm alarm = new Alarm("r", "rule", Severity.HIGH, "message", "host");
        alarm.setSourceAlertId(java.util.UUID.randomUUID().toString()); alarm.setRiskScore(45);
        alarm.setRiskLevel("MEDIUM"); alarm.setInitialRiskScore(45); alarm.setInitialRiskLevel("MEDIUM");
        return alarms.saveAndFlush(alarm);
    }
    @Test void snapshotAndEnrichedEventCommitOnceAndPreserveAnalystState() throws Exception {
        Alarm alarm = create(); alarm.setStatus("INVESTIGATING"); alarms.saveAndFlush(alarm);
        committer.complete(TenantContext.require(), alarm.getId(), "[{\"ioc\":\"example.com\"}]", 85);
        committer.complete(TenantContext.require(), alarm.getId(), "[]", 10);
        Alarm current = alarms.findByTenantIdAndId(TenantContext.require(), alarm.getId()).orElseThrow();
        assertThat(current.getStatus()).isEqualTo("INVESTIGATING");
        assertThat(current.getInitialRiskScore()).isEqualTo(45);
        assertThat(current.getRiskScore()).isEqualTo(85);
        assertThat(current.getEnrichedAt()).isNotNull();
        var rows = deliveries.findByTenantIdAndAlarmIdOrderByDestinationAsc(TenantContext.require(), alarm.getId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getDestination()).isEqualTo("SOAR_ENRICHED");
            assertThat(row.getPayload()).contains("alert.enriched", "\"riskScore\":85", "\"initialRiskScore\":45");
        });
    }
    @Test void deliveryWriteFailureRollsBackRiskAndCompletionMarker() {
        Alarm alarm = create();
        doThrow(new IllegalStateException("database failed")).when(registrar).registerEnriched(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> committer.complete(TenantContext.require(), alarm.getId(), "[]", 90)).hasMessageContaining("database failed");
        Alarm current = alarms.findByTenantIdAndId(TenantContext.require(), alarm.getId()).orElseThrow();
        assertThat(current.getEnrichedAt()).isNull(); assertThat(current.getRiskScore()).isEqualTo(45);
    }
}
