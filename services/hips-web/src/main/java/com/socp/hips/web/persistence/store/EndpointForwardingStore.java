package com.socp.hips.web.persistence.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Durable ownership and bounded admission; all cross-replica coordination lives in SQL. */
@Service
public class EndpointForwardingStore {
    private final JdbcTemplate jdbc;
    private final int maxPending;
    private final int maxTenantPending;
    private final int maxAttempts;

    public EndpointForwardingStore(JdbcTemplate jdbc,
            @Value("${socp.hips.forwarding.max-pending:100000}") int maxPending,
            @Value("${socp.hips.forwarding.max-tenant-pending:10000}") int maxTenantPending,
            @Value("${socp.hips.forwarding.max-attempts:20}") int maxAttempts) {
        if (maxPending < 1 || maxTenantPending < 1 || maxAttempts < 1 || maxAttempts > 1000)
            throw new IllegalArgumentException("Invalid endpoint forwarding limits");
        this.jdbc = jdbc;
        this.maxPending = maxPending;
        this.maxTenantPending = maxTenantPending;
        this.maxAttempts = maxAttempts;
    }

    @Transactional
    public void enqueue(String eventId, String tenant, String payload, Instant now) {
        enqueue(eventId, tenant, payload, now, null);
    }

    /** Must be acquired before history/heartbeat writes by every collection writer. */
    @Transactional
    public void lockAdmission() {
        jdbc.queryForObject("select id from t_endpoint_forwarding_admission where id=1 for update", Integer.class);
    }

    public record RequestIdentity(String producerHash, String keyHash, String fingerprint) { }
    public record Replay(String eventId, String payload, String fingerprint) { }

    /** Caller holds the admission lock through lookup and insertion in the same transaction. */
    @Transactional
    public Optional<Replay> findRequest(String tenant, RequestIdentity request) {
        return jdbc.query("""
                select event_id,payload_json,request_fingerprint from t_endpoint_forwarding
                where tenant_id=? and producer_hash=? and request_key_hash=? for update
                """, (rs, row) -> new Replay(rs.getString(1), rs.getString(2), rs.getString(3)),
                tenant, request.producerHash(), request.keyHash()).stream().findFirst();
    }

    @Transactional
    public void enqueue(String eventId, String tenant, String payload, Instant now, RequestIdentity request) {
        lockAdmission();
        Long total;
        // Admission is globally serialized. Only this aggregate needs cross-tenant visibility;
        // the caller's tenant scope is restored before inspecting or inserting its receipt.
        try (var ignored = com.socp.platform.tenant.context.TenantContext.openSystem()) {
            total = jdbc.queryForObject("select count(*) from t_endpoint_forwarding where status <> 'DELIVERED'", Long.class);
        }
        Long owned = jdbc.queryForObject("select count(*) from t_endpoint_forwarding where tenant_id=? and status <> 'DELIVERED'", Long.class, tenant);
        if (total >= maxPending || owned >= maxTenantPending)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Endpoint forwarding backlog is full");
        jdbc.update("""
                insert into t_endpoint_forwarding(event_id,tenant_id,payload_json,status,attempts,next_attempt_at,created_at,
                    producer_hash,request_key_hash,request_fingerprint)
                values(?,?,?,'PENDING',0,?,?,?,?,?)
                """, eventId, tenant, payload, Timestamp.from(now), Timestamp.from(now),
                request == null ? null : request.producerHash(), request == null ? null : request.keyHash(),
                request == null ? null : request.fingerprint());
    }

    public record Claim(String eventId, String tenantId, String payload, String token, int attempts) { }

    /** A null identity is reserved for the internal scheduler; HTTP resources always supply a tenant. */
    @Transactional
    public Optional<Claim> claim(String tenant, String eventId, Instant now) {
        String scope = tenant == null ? "" : " and tenant_id=? and event_id=?";
        Object[] args = tenant == null ? new Object[]{Timestamp.from(now)}
                : new Object[]{Timestamp.from(now), tenant, eventId};
        List<Claim> candidates = jdbc.query("""
                select event_id,tenant_id,payload_json,attempts from t_endpoint_forwarding
                where status in ('PENDING','PROCESSING') and next_attempt_at<=?
                """ + scope + " order by next_attempt_at,event_id limit 1 for update skip locked",
                (rs, row) -> new Claim(rs.getString(1), rs.getString(2), rs.getString(3),
                        UUID.randomUUID().toString(), rs.getInt(4) + 1), args);
        if (candidates.isEmpty()) return Optional.empty();
        Claim claim = candidates.getFirst();
        if (claim.attempts() > maxAttempts) {
            jdbc.update("update t_endpoint_forwarding set status='DEAD',claim_token=null,last_error=? where event_id=?",
                    "Retry budget exhausted after expired claim", claim.eventId());
            return Optional.empty();
        }
        jdbc.update("""
                update t_endpoint_forwarding set status='PROCESSING',claim_token=?,attempts=?,next_attempt_at=?
                where event_id=?
                """, claim.token(), claim.attempts(), Timestamp.from(now.plusSeconds(120)), claim.eventId());
        return Optional.of(claim);
    }

    @Transactional
    public boolean complete(Claim claim, Instant now) {
        return jdbc.update("""
                update t_endpoint_forwarding set status='DELIVERED',claim_token=null,delivered_at=?,last_error=null
                where event_id=? and tenant_id=? and status='PROCESSING' and claim_token=?
                """, Timestamp.from(now), claim.eventId(), claim.tenantId(), claim.token()) == 1;
    }

    @Transactional
    public boolean fail(Claim claim, Instant now, String reason) {
        String message = reason == null ? "Unacknowledged delivery" : reason.substring(0, Math.min(256, reason.length()));
        long delay = Math.min(300, 1L << Math.min(9, claim.attempts()));
        return jdbc.update("""
                update t_endpoint_forwarding set status=?,claim_token=null,next_attempt_at=?,last_error=?
                where event_id=? and tenant_id=? and status='PROCESSING' and claim_token=?
                """, claim.attempts() >= maxAttempts ? "DEAD" : "PENDING", Timestamp.from(now.plusSeconds(delay)),
                message, claim.eventId(), claim.tenantId(), claim.token()) == 1;
    }

    @Transactional(readOnly = true)
    public String status(String tenant, String eventId) {
        List<String> states = jdbc.query("select status from t_endpoint_forwarding where tenant_id=? and event_id=?",
                (rs, row) -> rs.getString(1), tenant, eventId);
        return states.isEmpty() ? "UNKNOWN" : states.getFirst();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String tenant, String status, int limit) {
        return jdbc.query("""
                select event_id,status,attempts,created_at,next_attempt_at,delivered_at,last_error
                from t_endpoint_forwarding where tenant_id=? and status=? order by created_at,event_id limit ?
                """, (rs, row) -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("eventId", rs.getString(1)); item.put("status", rs.getString(2));
                    item.put("attempts", rs.getInt(3)); item.put("createdAt", rs.getTimestamp(4).toInstant());
                    item.put("nextAttemptAt", rs.getTimestamp(5).toInstant());
                    item.put("deliveredAt", rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant());
                    item.put("lastError", rs.getString(7)); return item;
                }, tenant, status, limit);
    }

    @Transactional
    public boolean requeue(String tenant, String eventId, Instant now) {
        return jdbc.update("""
                update t_endpoint_forwarding set status='PENDING',attempts=0,claim_token=null,next_attempt_at=?,last_error=null
                where tenant_id=? and event_id=? and status='DEAD'
                """, Timestamp.from(now), tenant, eventId) == 1;
    }

    @Transactional
    public int prune(Instant cutoff) {
        List<String> ids = jdbc.query("""
                select event_id from t_endpoint_forwarding where status='DELIVERED' and delivered_at<?
                order by delivered_at,event_id limit 100 for update skip locked
                """, (rs, row) -> rs.getString(1), Timestamp.from(cutoff));
        for (String id : ids) jdbc.update("delete from t_endpoint_forwarding where event_id=? and status='DELIVERED'", id);
        return ids.size();
    }
}
