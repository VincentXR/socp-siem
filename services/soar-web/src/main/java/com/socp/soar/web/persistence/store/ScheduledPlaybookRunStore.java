package com.socp.soar.web.persistence.store;

import com.socp.soar.web.persistence.entity.ScheduledPlaybookRunEntity;
import com.socp.soar.web.persistence.repository.ScheduledPlaybookRunRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Database-backed at-most-once claim shared by every SOAR instance. */
@Component
public class ScheduledPlaybookRunStore {

    private final ScheduledPlaybookRunRepository repository;

    public ScheduledPlaybookRunStore(ScheduledPlaybookRunRepository repository) {
        this.repository = repository;
    }

    public Claim claim(String tenant, String playbookId, Instant scheduledFor) {
        Instant fireTime = scheduledFor.truncatedTo(ChronoUnit.MINUTES);
        String id = claimId(tenant, playbookId, fireTime);
        Instant now = Instant.now();
        ScheduledPlaybookRunEntity row = new ScheduledPlaybookRunEntity();
        row.setId(id);
        row.setTenantId(tenant);
        row.setPlaybookId(playbookId);
        row.setScheduledFor(fireTime);
        row.setStatus("PROCESSING");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            repository.saveAndFlush(row);
            return new Claim(id, tenant, playbookId, fireTime);
        } catch (DataIntegrityViolationException duplicateClaim) {
            return null;
        }
    }

    public void complete(Claim claim) {
        update(claim, "COMPLETED", null);
    }

    public void fail(Claim claim, Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? "unknown failure" : failure.getMessage();
        update(claim, "FAILED", message.length() <= 1024 ? message : message.substring(0, 1024));
    }

    private void update(Claim claim, String status, String error) {
        repository.findByIdAndTenantId(claim.id(), claim.tenant()).ifPresent(row -> {
            row.setStatus(status);
            row.setLastError(error);
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        });
    }

    static String claimId(String tenant, String playbookId, Instant scheduledFor) {
        String source = tenant + "\u0000" + playbookId + "\u0000" + scheduledFor;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record Claim(String id, String tenant, String playbookId, Instant scheduledFor) { }
}
