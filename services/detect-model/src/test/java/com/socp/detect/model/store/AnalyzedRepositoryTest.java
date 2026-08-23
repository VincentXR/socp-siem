package com.socp.detect.model.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
class AnalyzedRepositoryTest {

    @Autowired
    private AnalyzedRepository repository;

    @Test
    void pagesAggregatesAndDeletesTheDurableProjection() {
        Instant now = Instant.parse("2026-08-24T00:00:00Z");
        repository.save(new AnalyzedEntity("tenant-a", "a-old", "r1", "Rule 1",
                "HIGH", "old", "host-1", now.minusSeconds(7200)));
        repository.save(new AnalyzedEntity("tenant-a", "a-new", "r2", "Rule 2",
                "LOW", "new", "host-2", now));
        repository.save(new AnalyzedEntity("tenant-b", "b-new", "r3", "Rule 3",
                "CRITICAL", "other tenant", "host-3", now));
        repository.flush();

        var page = repository.findByTenantId("tenant-a", PageRequest.of(0, 1,
                Sort.by(Sort.Order.desc("ts"), Sort.Order.desc("id"))));
        assertEquals(2, page.getTotalElements());
        assertEquals("a-new", page.getContent().getFirst().getAlertId());

        Map<String, Long> counts = repository.countBySeverity("tenant-a").stream()
                .collect(Collectors.toMap(row -> String.valueOf(row[0]), row -> ((Number) row[1]).longValue()));
        assertEquals(Map.of("HIGH", 1L, "LOW", 1L), counts);

        assertEquals(1, repository.deleteBefore(now.minusSeconds(3600)));
        assertEquals(1, repository.countByTenantId("tenant-a"));
        assertEquals(1, repository.countByTenantId("tenant-b"));
    }
}
