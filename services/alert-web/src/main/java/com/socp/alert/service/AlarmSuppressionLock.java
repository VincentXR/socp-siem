package com.socp.alert.service;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One durable row per tenant serializes window quota and upserts across replicas. */
@Component
public class AlarmSuppressionLock {
    private final JdbcTemplate jdbc;

    public AlarmSuppressionLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Suppression lock requires an active transaction");
        }
        String tenant = TenantContext.require();
        boolean h2 = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().startsWith("H2")));
        if (h2) {
            jdbc.update("MERGE INTO t_alarm_suppression_lock (tenant_id) KEY (tenant_id) VALUES (?)", tenant);
        } else {
            jdbc.update("INSERT INTO t_alarm_suppression_lock (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        }
        String locked = jdbc.queryForObject(
                "SELECT tenant_id FROM t_alarm_suppression_lock WHERE tenant_id=? FOR UPDATE", String.class, tenant);
        if (!tenant.equals(locked)) throw new IllegalStateException("Suppression tenant lock was not acquired");
    }
}
