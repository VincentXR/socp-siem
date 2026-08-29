package com.socp.soar.web.persistence.repository;

import com.socp.soar.web.persistence.entity.PlaybookEntity;
import com.socp.soar.web.persistence.entity.ScheduledPlaybookRunEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
class ScheduledPlaybookRepositoryTest {

    @Autowired
    private PlaybookRepository playbooks;

    @Autowired
    private ScheduledPlaybookRunRepository scheduledRuns;

    @Test
    void enumeratesOnlyTenantsWithEnabledPlaybooks() {
        playbooks.save(playbook("a-1", "tenant-a", true));
        playbooks.save(playbook("a-2", "tenant-a", true));
        playbooks.save(playbook("b-1", "tenant-b", false));
        playbooks.save(playbook("c-1", "tenant-c", true));
        playbooks.flush();

        assertEquals(java.util.List.of("tenant-a", "tenant-c"),
                playbooks.findTenantIdsWithEnabledPlaybooks());
    }

    @Test
    void uniqueFireTimeRejectsASecondInstanceClaim() {
        Instant fire = Instant.parse("2026-08-29T03:30:00Z");
        scheduledRuns.saveAndFlush(run("claim-1", "tenant-a", "pb-1", fire));

        assertThrows(DataIntegrityViolationException.class, () ->
                scheduledRuns.saveAndFlush(run("claim-2", "tenant-a", "pb-1", fire)));
    }

    private static PlaybookEntity playbook(String id, String tenant, boolean enabled) {
        PlaybookEntity row = new PlaybookEntity();
        row.setId(id);
        row.setTenantId(tenant);
        row.setName(id);
        row.setTrigger("schedule daily 03:30");
        row.setActions("[]");
        row.setEnabled(enabled);
        row.setStatus(enabled ? "ACTIVE" : "DRAFT");
        row.setCreatedAt(Instant.EPOCH);
        return row;
    }

    private static ScheduledPlaybookRunEntity run(String id, String tenant, String playbook,
                                                   Instant fire) {
        ScheduledPlaybookRunEntity row = new ScheduledPlaybookRunEntity();
        row.setId(id);
        row.setTenantId(tenant);
        row.setPlaybookId(playbook);
        row.setScheduledFor(fire);
        row.setStatus("PROCESSING");
        row.setCreatedAt(fire);
        row.setUpdatedAt(fire);
        return row;
    }
}
