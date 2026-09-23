package com.socp.incident.web.persistence;

import com.socp.incident.web.domain.Case;
import com.socp.incident.web.domain.TimelineEvent;
import com.socp.incident.web.persistence.entity.CaseTimelineEntity;
import com.socp.incident.web.persistence.repository.CaseTimelineRepository;
import com.socp.incident.web.persistence.store.CaseStore;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Database-backed proof of bounded hydration and stable ordering at timestamp ties. */
final class CaseTimelineReadAssertions {
    private CaseTimelineReadAssertions() { }

    static void verify(CaseStore store, CaseTimelineRepository repository, EntityManagerFactory factory) {
        String tenant = TenantContext.require();
        Case incident = store.save(Case.create("large timeline", "host-bounded", "HIGH"));
        Instant time = Instant.parse("2026-09-21T01:00:00Z");
        List<CaseTimelineEntity> rows = new ArrayList<>();
        for (int index = 0; index < 521; index++) {
            CaseTimelineEntity row = new CaseTimelineEntity();
            row.setId(String.format("00000000-0000-0000-0000-%012d", index));
            row.setTenantId(tenant);
            row.setCaseId(incident.id());
            row.setEventKey("bounded-event-" + index);
            row.setTs(time);
            row.setCreatedAt(time);
            row.setType("NOTE");
            row.setSource("test");
            row.setMessage("event-" + index);
            rows.add(row);
        }
        // Heap/insertion order must not accidentally satisfy the timestamp-tie oracle.
        Collections.shuffle(rows, new Random(42));
        repository.saveAllAndFlush(rows);

        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            Case loaded = store.get(incident.id());
            assertThat(loaded.timeline()).hasSize(500);
            assertThat(loaded.timeline().getFirst().message()).isEqualTo("event-0");
            assertThat(loaded.timeline().getLast().message()).isEqualTo("event-499");
            // One case plus at most 500 events, rather than loading all 521 then slicing.
            assertThat(statistics.getEntityLoadCount()).isEqualTo(501);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 27; page++) {
            var result = store.timeline(incident.id(), page, 20);
            assertThat(result.getTotalElements()).isEqualTo(521);
            paged.addAll(result.getContent().stream().map(CaseTimelineEntity::getMessage).toList());
        }
        assertThat(paged).containsExactlyElementsOf(java.util.stream.IntStream.range(0, 521)
                .mapToObj(index -> "event-" + index).toList());
        assertThat(store.get(incident.id()).timeline()).extracting(TimelineEvent::message)
                .doesNotContain("event-520");
        TenantContext.set(tenant + "-other");
        assertThat(store.get(incident.id())).isNull();
        assertThat(store.timeline(incident.id(), 0, 20)).isEmpty();
    }
}
