package com.socp.notify.web.service;

import com.socp.platform.tenant.context.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

/** Short database transactions own admission and receipt fencing; never external I/O. */
@Service
public class NotificationDeliveryState {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public NotificationDeliveryState(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(3);
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(3);
        String product = this.jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        if (!"PostgreSQL".equals(product) && !"H2".equals(product)) {
            throw new IllegalStateException("Unsupported notification database: " + product);
        }
        postgres = "PostgreSQL".equals(product);
    }

    public Claim claim(String alarmId, String channelId) {
        String tenant = TenantContext.require();
        String id = deliveryId(tenant, alarmId, channelId);
        return transactions.execute(tx -> {
            if (postgres) {
                jdbc.update("insert into t_notification_delivery (id, tenant_id, alarm_id, channel_id, result_json) "
                        + "values (?, ?, ?, ?, '{}') on conflict do nothing", id, tenant, alarmId, channelId);
            } else {
                try {
                    jdbc.update("""
                            merge into t_notification_delivery as target
                            using (values (cast(? as varchar(36)), cast(? as varchar(64)),
                                cast(? as varchar(255)), cast(? as varchar(64)))) as incoming(id, tenant_id, alarm_id, channel_id)
                            on target.id = incoming.id
                            when not matched then insert (id, tenant_id, alarm_id, channel_id, result_json)
                                values (incoming.id, incoming.tenant_id, incoming.alarm_id, incoming.channel_id, '{}')
                            """, id, tenant, alarmId, channelId);
                } catch (DuplicateKeyException concurrentInsert) {
                    // H2 rolls back only this statement; the locked read must find the winner.
                }
            }
            var rows = jdbc.query("select delivered_at, result_json, next_attempt_at from t_notification_delivery "
                            + "where tenant_id = ? and id = ? for update skip locked",
                    (rs, index) -> new Receipt(rs.getTimestamp(1), rs.getString(2), rs.getTimestamp(3)), tenant, id);
            if (rows.isEmpty()) return new Claim(null, null);
            var row = rows.getFirst();
            if (row.deliveredAt() != null) return new Claim(null, row.json());
            var now = jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
            if (row.nextAttempt() != null && row.nextAttempt().toInstant().isAfter(now)) return new Claim(null, null);
            String token = UUID.randomUUID().toString();
            jdbc.update("update t_notification_delivery set claim_token = ?, next_attempt_at = ? where tenant_id = ? and id = ?",
                    token, Timestamp.from(now.plusSeconds(120)), tenant, id);
            return new Claim(token, null);
        });
    }

    /** A stale callback cannot overwrite a later attempt or completed receipt. */
    public boolean finish(String alarmId, String channelId, String token, String json, boolean success) {
        if (token == null) return false;
        String tenant = TenantContext.require();
        String id = deliveryId(tenant, alarmId, channelId);
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            var now = jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
            return jdbc.update("update t_notification_delivery set result_json = ?, delivered_at = ?, "
                            + "claim_token = null, next_attempt_at = ? where tenant_id = ? and id = ? "
                            + "and claim_token = ? and delivered_at is null", json,
                    success ? Timestamp.from(now) : null, success ? null : Timestamp.from(now.plusSeconds(5)),
                    tenant, id, token) == 1;
        }));
    }

    static String deliveryId(String tenant, String alarmId, String channelId) {
        return UUID.nameUUIDFromBytes((tenant + "\u0000" + alarmId + "\u0000" + channelId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record Claim(String token, String receiptJson) { }
    private record Receipt(Timestamp deliveredAt, String json, Timestamp nextAttempt) { }
}
