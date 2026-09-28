package com.socp.incident.web.service;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Cross-replica serialization for automatic case aggregation.
 *
 * <p>One tenant owns at most 256 lazily-created lock rows. Hash collisions only
 * serialize unrelated entities; they cannot merge them. Keeping the key-space
 * bounded avoids a lock row for every attacker-controlled entity value.</p>
 */
@Component
public class IncidentAggregationLock {

    static final int SHARDS = 256;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public IncidentAggregationLock(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void lock(String entity) {
        if (entity == null || entity.isBlank()) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident aggregation lock requires an active transaction");
        }
        String tenant = TenantContext.require();
        int shard = shard(entity);
        try {
            requiresNew.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO t_incident_merge_lock (tenant_id, shard_id, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, tenant, shard));
        } catch (DuplicateKeyException alreadyExists) {
            // The isolated insert transaction was rolled back. The durable row
            // is now safe to lock in the caller's business transaction.
        }
        Integer locked = jdbc.queryForObject("""
                SELECT shard_id FROM t_incident_merge_lock
                 WHERE tenant_id=? AND shard_id=?
                 FOR UPDATE
                """, Integer.class, tenant, shard);
        if (locked == null || locked != shard) {
            throw new IllegalStateException("Unable to acquire incident aggregation lock");
        }
    }

    static int shard(String entity) {
        byte[] value = entity.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte item : value) {
            hash ^= item & 0xff;
            hash *= 0x01000193;
        }
        return Math.floorMod(hash, SHARDS);
    }
}
