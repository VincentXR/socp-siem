package com.socp.incident.web.service;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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

    public IncidentAggregationLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String entity) {
        lockAll(entity);
    }

    /**
     * Acquires every requested shard in deterministic order. Automatic
     * aggregation locks both the alarm identity and the subject identity;
     * sorting prevents two concurrent multi-key requests from deadlocking.
     */
    public void lockAll(String... identities) {
        int[] shards = Arrays.stream(identities == null ? new String[0] : identities)
                .filter(identity -> identity != null && !identity.isBlank())
                .mapToInt(IncidentAggregationLock::shard)
                .distinct()
                .sorted()
                .toArray();
        if (shards.length == 0) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Incident aggregation lock requires an active transaction");
        }
        String tenant = TenantContext.require();
        boolean h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().startsWith("H2")));
        for (int shard : shards) acquire(tenant, shard, h2);
    }

    private void acquire(String tenant, int shard, boolean h2) {
        if (h2) {
            jdbc.update("""
                    MERGE INTO t_incident_merge_lock (tenant_id, shard_id, created_at)
                    KEY (tenant_id, shard_id) VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, tenant, shard);
        } else {
            jdbc.update("""
                    INSERT INTO t_incident_merge_lock (tenant_id, shard_id, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (tenant_id, shard_id) DO NOTHING
                    """, tenant, shard);
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
