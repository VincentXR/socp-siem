package com.socp.incident.web.persistence;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.store.CaseStore;
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
}
