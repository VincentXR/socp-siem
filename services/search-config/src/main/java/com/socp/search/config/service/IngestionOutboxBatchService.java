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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Bounded transactions for routing-key leases and ordered prefixes; broker I/O stays outside them. */
@Service
@SearchRuntimeRole(SearchRuntimeRole.Role.WORKER)
public class IngestionOutboxBatchService {
    public static final int MAX_BATCH_SIZE = 32;
    public static final int MAX_PREFIX_ROWS = 200;
    public static final int MAX_ROWS_PER_KEY = 16;
    // Keep the target row on its ID index, but let predecessor probes include
    // status in their key index bounds. Scalar statuses and OFFSET 0 preserve
    // correlated active-state probes even when a fresh tenant is absent from
    // statistics; CASE predicates here would scan retained key history per row.
    private static final String CLAIM_SQL = "update t_ingestion_outbox e set status='PROCESSING', claim_token=?, "
            + "processing_key=concat(concat(e.tenant_id, ':'), e.routing_key), "
            + "updated_at=?, claimed_at=?, attempts=attempts+1 "
            + "where e.id=? and case when e.tenant_id=? and e.status='PENDING' then e.attempts else null end=? "
            + "and e.attempts<? and e.next_attempt_at<=? "
            + "and not exists (select 1 from t_ingestion_outbox blocker "
            + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
            + "and blocker.status=(select cast('PROCESSING' as varchar)) offset 0) "
            + "and not exists (select 1 from t_ingestion_outbox blocker "
            + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
            + "and blocker.status=(select cast('PENDING' as varchar)) "
            + "and blocker.sequence_no<=e.sequence_no and (blocker.sequence_no<e.sequence_no or blocker.id<e.id) offset 0)";
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
                    bindClaim(statement, event, tenant, token, maxAttempts, now);
                    statement.addBatch();
                }
                return acknowledgedClaims(statement, ordered);
            }
        });
    }

    /** Reserve bounded contiguous prefixes in one transaction, retaining one unique lease per key. */
    @Transactional
    public List<IngestionOutboxEvent> claimOrderedPrefixes(List<IngestionOutboxEvent> candidates,
                                                          String token, int maxAttempts, Instant now) {
        String tenant = validatePrefixes(candidates, token);
        if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        if (candidates.isEmpty()) return List.of();
        var ordered = candidates.stream().sorted(Comparator.comparing(IngestionOutboxEvent::getRoutingKey)
                .thenComparing(IngestionOutboxEvent::getSequenceNo).thenComparing(IngestionOutboxEvent::getId)).toList();
        var heads = new java.util.LinkedHashMap<String, IngestionOutboxEvent>();
        ordered.forEach(event -> heads.putIfAbsent(event.getRoutingKey(), event));
        entityManager.flush();
        return entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection -> {
            List<IngestionOutboxEvent> claimedHeads;
            try (var statement = connection.prepareStatement(CLAIM_SQL)) {
                for (var head : heads.values()) {
                    bindClaim(statement, head, tenant, token, maxAttempts, now);
                    statement.addBatch();
                }
                claimedHeads = acknowledgedClaims(statement, List.copyOf(heads.values()));
            }
            var anchors = new java.util.HashMap<String, String>();
            claimedHeads.forEach(event -> anchors.put(event.getRoutingKey(), event.getId()));
            var followers = ordered.stream().filter(event -> anchors.containsKey(event.getRoutingKey())
                    && !event.getId().equals(anchors.get(event.getRoutingKey()))).toList();
            var claimed = new ArrayList<>(claimedHeads);
            if (!followers.isEmpty()) {
                claimed.addAll(claimFollowers(connection, followers, claimedHeads, tenant, token, maxAttempts, now));
            }
            claimed.sort(Comparator.comparing(IngestionOutboxEvent::getRoutingKey)
                    .thenComparing(IngestionOutboxEvent::getSequenceNo).thenComparing(IngestionOutboxEvent::getId));
            return List.copyOf(claimed);
        });
    }

    private static List<IngestionOutboxEvent> claimFollowers(java.sql.Connection connection,
            List<IngestionOutboxEvent> followers, List<IngestionOutboxEvent> heads,
            String tenant, String token, int maxAttempts, Instant now) throws java.sql.SQLException {
        // Freeze candidate eligibility before excluding these IDs from the
        // predecessor check. Otherwise a concurrent retry/delay could make an
        // earlier row ineligible while a later row is claimed from an old snapshot.
        var eligible = lockEligibleFollowers(connection, followers, tenant, maxAttempts, now);
        if (eligible.isEmpty()) return List.of();
        int updated;
        try (var statement = connection.prepareStatement(followerClaimSql(eligible.size(), heads.size()))) {
            statement.setString(1, token);
            statement.setTimestamp(2, Timestamp.from(now));
            statement.setTimestamp(3, Timestamp.from(now));
            int parameter = bindIds(statement, 4, eligible);
            statement.setString(parameter++, tenant);
            statement.setInt(parameter++, maxAttempts);
            statement.setTimestamp(parameter++, Timestamp.from(now));
            parameter = bindIds(statement, parameter, heads);
            statement.setString(parameter++, token);
            statement.setString(parameter++, token);
            bindIds(statement, parameter, eligible);
            updated = statement.executeUpdate();
        }
        if (updated < 0 || updated > eligible.size()) throw new java.sql.SQLException("ambiguous prefix claim receipt");
        if (updated == 0) return List.of();
        var owned = new HashSet<String>();
        String receiptSql = "select id from t_ingestion_outbox where id in (" + placeholders(eligible.size())
                + ") and case when tenant_id=? and status='PROCESSING' then claim_token else null end=?";
        try (var statement = connection.prepareStatement(receiptSql)) {
            int parameter = bindIds(statement, 1, eligible);
            statement.setString(parameter++, tenant);
            statement.setString(parameter, token);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) owned.add(rows.getString(1));
            }
        }
        if (owned.size() != updated) throw new java.sql.SQLException("incomplete prefix claim receipt");
        var blocked = new HashSet<String>();
        var claimed = new ArrayList<IngestionOutboxEvent>();
        for (var event : eligible) {
            if (!owned.contains(event.getId())) blocked.add(event.getRoutingKey());
            else {
                if (blocked.contains(event.getRoutingKey())) throw new java.sql.SQLException("noncontiguous prefix claim receipt");
                claimed.add(event);
            }
        }
        return List.copyOf(claimed);
    }

    private static List<IngestionOutboxEvent> lockEligibleFollowers(java.sql.Connection connection,
            List<IngestionOutboxEvent> followers, String tenant, int maxAttempts, Instant now) throws java.sql.SQLException {
        var candidates = new HashMap<String, IngestionOutboxEvent>();
        followers.forEach(event -> candidates.put(event.getId(), event));
        var eligible = new HashSet<String>();
        String sql = "select id, routing_key, sequence_no, status, attempts, next_attempt_at from t_ingestion_outbox "
                + "where id in (" + placeholders(followers.size()) + ") and case when tenant_id=? then 1 else 0 end=1 "
                + "order by routing_key, sequence_no, id for update";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(bindIds(statement, 1, followers), tenant);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var event = candidates.get(rows.getString("id"));
                    if ("PENDING".equals(rows.getString("status"))
                            && event.getRoutingKey().equals(rows.getString("routing_key"))
                            && event.getSequenceNo() == rows.getLong("sequence_no")
                            && event.getAttempts() == rows.getInt("attempts") && event.getAttempts() < maxAttempts
                            && !rows.getTimestamp("next_attempt_at").toInstant().isAfter(now)) {
                        eligible.add(event.getId());
                    }
                }
            }
        }
        return followers.stream().filter(event -> eligible.contains(event.getId())).toList();
    }

    private static String followerClaimSql(int followers, int heads) {
        // One server UPDATE sees the entire locked set. An omitted or ineligible
        // pending predecessor still blocks its suffix. Probe the unique lease
        // key before checking anchor metadata, even while status statistics lag.
        return "update t_ingestion_outbox e set status='PROCESSING', claim_token=?, "
                + "processing_key=null, updated_at=?, claimed_at=?, attempts=attempts+1 "
                + "where e.id in (" + placeholders(followers) + ") "
                + "and case when e.tenant_id=? and e.status='PENDING' then 1 else 0 end=1 "
                + "and e.attempts<? and e.next_attempt_at<=? "
                + "and exists (select 1 from t_ingestion_outbox anchor "
                + "where anchor.processing_key=concat(concat(e.tenant_id, ':'), e.routing_key) "
                + "and case when anchor.id in (" + placeholders(heads) + ") "
                + "and anchor.tenant_id=e.tenant_id and anchor.routing_key=e.routing_key "
                + "and anchor.status='PROCESSING' then anchor.claim_token else null end=? offset 0) "
                + "and not exists (select 1 from t_ingestion_outbox blocker "
                + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
                + "and blocker.status=(select cast('PROCESSING' as varchar)) "
                + "and (blocker.claim_token is null or blocker.claim_token<>?) offset 0) "
                + "and not exists (select 1 from t_ingestion_outbox blocker "
                + "where blocker.tenant_id=e.tenant_id and blocker.routing_key=e.routing_key "
                + "and blocker.status=(select cast('PENDING' as varchar)) "
                + "and blocker.sequence_no<=e.sequence_no and (blocker.sequence_no<e.sequence_no or blocker.id<e.id) "
                + "and blocker.id not in (" + placeholders(followers) + ") offset 0)";
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int bindIds(java.sql.PreparedStatement statement, int parameter,
                               List<IngestionOutboxEvent> rows) throws java.sql.SQLException {
        for (var row : rows) statement.setString(parameter++, row.getId());
        return parameter;
    }

    /** An uncertain or partial ownership receipt rolls back the whole prefix finalization. */
    @Transactional
    public int markOrderedAcknowledged(List<IngestionOutboxEvent> acknowledged, String token, Instant now) {
        String tenant = validatePrefixes(acknowledged, token);
        if (acknowledged.isEmpty()) return 0;
        int updated = repository.markPublishedBatch(tenant,
                acknowledged.stream().map(IngestionOutboxEvent::getId).toList(), now, token);
        if (updated != acknowledged.size()) throw new IllegalStateException("ordered outbox claim ownership changed");
        return updated;
    }

    private static void bindClaim(java.sql.PreparedStatement statement, IngestionOutboxEvent event,
                                  String tenant, String token, int maxAttempts, Instant now) throws java.sql.SQLException {
        statement.setString(1, token);
        statement.setTimestamp(2, Timestamp.from(now));
        statement.setTimestamp(3, Timestamp.from(now));
        statement.setString(4, event.getId());
        statement.setString(5, tenant);
        statement.setInt(6, event.getAttempts());
        statement.setInt(7, maxAttempts);
        statement.setTimestamp(8, Timestamp.from(now));
    }

    private static List<IngestionOutboxEvent> acknowledgedClaims(java.sql.PreparedStatement statement,
                                                                List<IngestionOutboxEvent> rows) throws java.sql.SQLException {
        int[] counts = statement.executeBatch();
        if (counts.length != rows.size()) throw new java.sql.SQLException("incomplete claim batch receipt");
        List<IngestionOutboxEvent> claimed = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 1) claimed.add(rows.get(i));
            else if (counts[i] != 0) throw new java.sql.SQLException("ambiguous claim batch receipt");
        }
        return List.copyOf(claimed);
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

    private static String validatePrefixes(List<IngestionOutboxEvent> events, String token) {
        String tenant = TenantContext.require();
        if (events == null || events.size() > MAX_PREFIX_ROWS) throw new IllegalArgumentException("outbox prefix limit is 200");
        if (token == null || token.isBlank() || token.length() > 36) throw new IllegalArgumentException("claim token is required");
        var ids = new HashSet<String>();
        var keys = new java.util.HashMap<String, Integer>();
        for (var event : events) {
            if (event == null || !tenant.equals(event.getTenantId()) || event.getSequenceNo() == null
                    || !ids.add(event.getId())) throw new IllegalArgumentException("invalid outbox prefix identity or tenant");
            if (keys.merge(event.getRoutingKey(), 1, Integer::sum) > MAX_ROWS_PER_KEY || keys.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("outbox prefix requires at most 32 keys and 16 rows per key");
            }
        }
        return tenant;
    }
}
