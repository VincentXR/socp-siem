package com.socp.search.config.infrastructure.kafka;

import com.socp.platform.data.outbox.OutboxRetryPolicy;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Short database transactions on either side of bounded, concurrent broker I/O. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.WORKER)
public class IngestionOutboxBatchStore {
    private final IngestionOutboxRepository repository;
    public IngestionOutboxBatchStore(IngestionOutboxRepository repository) { this.repository = repository; }

    public record Claim(String token, List<IngestionOutboxEvent> rows) {
        public Claim { rows = List.copyOf(rows); }
    }
    public record Settlement(int published, int retry, int dead, int lost) { }

    @Transactional
    public Claim claim(int maximum, int maxAttempts) {
        requirePublisherScope();
        if (maximum < 1 || maximum > 32) throw new IllegalArgumentException("claim bound must be 1..32");
        Instant now = Instant.now();
        List<IngestionOutboxEvent> rows = repository.findDueHeadsForUpdate(now, maxAttempts, maximum);
        String token = UUID.randomUUID().toString();
        if (!rows.isEmpty() && repository.claimHeadBatch(rows.stream().map(IngestionOutboxEvent::getId).toList(),
                now, maxAttempts, token) != rows.size()) {
            throw new IllegalStateException("outbox head claim changed within transaction");
        }
        return new Claim(token, rows);
    }

    @Transactional
    public Settlement settle(Claim claim, Set<String> acknowledged, int maxAttempts) {
        requirePublisherScope();
        if (claim.rows().size() > 32 || !claim.rows().stream().map(IngestionOutboxEvent::getId).toList().containsAll(acknowledged)) {
            throw new IllegalArgumentException("acknowledgement outside bounded claim");
        }
        Instant now = Instant.now();
        int published = acknowledged.isEmpty() ? 0 : repository.markBatchPublished(acknowledged, now, claim.token());
        int retry = 0, dead = 0;
        for (IngestionOutboxEvent row : claim.rows()) {
            if (acknowledged.contains(row.getId())) continue;
            var decision = OutboxRetryPolicy.afterClaim(row.getAttempts() + 1, maxAttempts, now,
                    "Kafka broker did not acknowledge the event", 900);
            if (decision.exhausted()) dead += repository.markDead(row.getId(), decision.error(), now, claim.token());
            else retry += repository.scheduleRetry(row.getId(), decision.nextAttemptAt(), decision.error(), now, claim.token());
        }
        return new Settlement(published, retry, dead, claim.rows().size() - published - retry - dead);
    }

    private static void requirePublisherScope() {
        // Only the scheduled publisher or its explicit system-scoped trigger
        // grants this authority. A transactional helper must never elevate callers.
        if (!TenantContext.isSystemScope()) throw new IllegalStateException("outbox publisher system scope required");
    }
}
