package com.socp.search.config.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Bounded transactions for independent routing keys; broker I/O stays outside the transaction. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.WORKER)
public class IngestionOutboxBatchService {
    public static final int MAX_BATCH_SIZE = 32;
    // Keep tenant/status fences conditional so stale selectivity estimates cannot
    // replace bounded ID/key lookups with scans of an entire tenant or status.
    private static final String CLAIM_SQL = "update t_ingestion_outbox e set status='PROCESSING', claim_token=?, "
            + "processing_key=concat(concat(e.tenant_id, ':'), e.routing_key), "
            + "updated_at=?, claimed_at=?, attempts=attempts+1 "
            + "where e.id=? and case when e.tenant_id=? and e.status='PENDING' then e.attempts else null end=? "
            + "and e.attempts<? and e.next_attempt_at<=? "
            + "and not exists (select 1 from t_ingestion_outbox blocker "
            + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
            + "and case when blocker.status='PROCESSING' then 1 else 0 end=1) "
            + "and not exists (select 1 from t_ingestion_outbox blocker "
            + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
            + "and case when blocker.status='PENDING' then 1 else 0 end=1 and blocker.sequence_no<e.sequence_no)";
    private final IngestionOutboxRepository repository;
    @PersistenceContext private EntityManager entityManager;

    public IngestionOutboxBatchService(IngestionOutboxRepository repository) {
        this.repository = repository;
    }

    /** A returned row has an acknowledged durable claim; a failed transaction returns no claims. */
    @Transactional
    public List<IngestionOutboxEvent> claimIndependent(List<IngestionOutboxEvent> candidates,
                                                       String token, int maxAttempts, Instant now) {
        String tenant = validate(candidates, token);
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        if (candidates.isEmpty()) return List.of();
        List<IngestionOutboxEvent> ordered = candidates.stream()
                .sorted(Comparator.comparing(IngestionOutboxEvent::getRoutingKey)
                        .thenComparing(IngestionOutboxEvent::getId)).toList();
        entityManager.flush();
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            // Preserve the same predecessor, attempt and processing-key fences as
            // the single-row path. Sort lease keys before taking row/index locks.
            try (var statement = connection.prepareStatement(CLAIM_SQL)) {
                for (IngestionOutboxEvent event : ordered) {
                    statement.setString(1, token);
                    statement.setTimestamp(2, Timestamp.from(now));
                    statement.setTimestamp(3, Timestamp.from(now));
                    statement.setString(4, event.getId());
                    statement.setString(5, tenant);
                    statement.setInt(6, event.getAttempts());
                    statement.setInt(7, maxAttempts);
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                if (counts.length != ordered.size()) throw new java.sql.SQLException("incomplete claim batch receipt");
                List<IngestionOutboxEvent> claimed = new ArrayList<>();
                for (int i = 0; i < counts.length; i++) {
                    if (counts[i] == 1) claimed.add(ordered.get(i));
                    else if (counts[i] != 0) throw new java.sql.SQLException("ambiguous claim batch receipt");
                }
                return List.copyOf(claimed);
            }
        });
    }

    /** Publish only acknowledged identities still owned by this exact claim token. */
    @Transactional
    public int markAcknowledged(List<IngestionOutboxEvent> acknowledged, String token, Instant now) {
        String tenant = validate(acknowledged, token);
        if (acknowledged.isEmpty()) return 0;
        return repository.markPublishedBatch(tenant, acknowledged.stream().map(IngestionOutboxEvent::getId).toList(),
                now, token);
    }

    private static String validate(List<IngestionOutboxEvent> events, String token) {
        String tenant = TenantContext.require();
        if (events == null || events.size() > MAX_BATCH_SIZE) throw new IllegalArgumentException("outbox batch limit is 32");
        if (token == null || token.isBlank() || token.length() > 36) throw new IllegalArgumentException("claim token is required");
        var ids = new HashSet<String>();
        var keys = new HashSet<String>();
        for (IngestionOutboxEvent event : events) {
            if (event == null || !tenant.equals(event.getTenantId())) throw new IllegalArgumentException("batch tenant mismatch");
            if (!ids.add(event.getId()) || !keys.add(event.getRoutingKey())) {
                throw new IllegalArgumentException("batch requires independent routing keys and identities");
            }
        }
        return tenant;
    }
}
