package com.socp.incident.web.persistence;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Real app repository proof for normalized timeline size and idempotency. */
@DataJpaTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(CaseStore.class)
class CaseTimelinePostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("incident")
            .withUsername("socp")
            .withPassword("socp");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private CaseStore store;

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void normalizedTimelineUsesUniqueEventKeyAndSupportsPaging() {
        Case incident = Case.create("timeline", "host-1", "HIGH");
        store.save(incident);
        TimelineEvent event = new TimelineEvent(Instant.now(), "NOTE", "same note", "test", null, "note-1");

        assertThat(store.appendTimeline(incident.id(), event)).isTrue();
        assertThat(store.appendTimeline(incident.id(), event)).isFalse();
        assertThat(store.timeline(incident.id(), 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(store.get(incident.id()).timeline()).hasSize(1);
    }
}
