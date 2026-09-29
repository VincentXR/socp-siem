package com.socp.notify.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.error.exception.ApiException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Short database transactions own admission and receipt fencing; never external I/O. */
@Service
public class NotificationDeliveryState {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
            var rows = jdbc.query("select delivered_at, result_json, next_attempt_at, recovery_generation "
                            + "from t_notification_delivery where tenant_id = ? and id = ? for update skip locked",
                    (rs, index) -> new Receipt(rs.getTimestamp(1), rs.getString(2), rs.getTimestamp(3),
                            rs.getInt(4)), tenant, id);
            if (rows.isEmpty()) return new Claim(null, null, 0);
            var row = rows.getFirst();
            if (row.deliveredAt() != null) return new Claim(null, row.json(), row.recoveryGeneration());
            var now = jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
            if (row.nextAttempt() != null && row.nextAttempt().toInstant().isAfter(now)) {
                return new Claim(null, null, row.recoveryGeneration());
            }
            String token = UUID.randomUUID().toString();
            jdbc.update("update t_notification_delivery set claim_token = ?, next_attempt_at = ? where tenant_id = ? and id = ?",
                    token, Timestamp.from(now.plusSeconds(120)), tenant, id);
            return new Claim(token, null, row.recoveryGeneration());
        });
    }

    /**
     * Reopens only terminal failures for an explicit operator replay. Unknown
     * receipts require a separate acknowledgement because the remote side may
     * already have accepted the previous attempt.
     */
    public Recovery recover(String alarmId, String reason, boolean confirmUnknown) {
        if (alarmId == null || alarmId.isBlank()) throw ApiException.badRequest("alarmId is required");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A recovery reason is required");
        String tenant = TenantContext.require();
        return transactions.execute(tx -> {
            var rows = jdbc.query("select id, channel_id, delivered_at, result_json, recovery_generation "
                            + "from t_notification_delivery where tenant_id = ? and alarm_id = ? for update",
                    (rs, index) -> new RecoveryRow(rs.getString(1), rs.getString(2), rs.getTimestamp(3),
                            rs.getString(4), rs.getInt(5)), tenant, alarmId);
            List<RecoveryItem> eligible = new ArrayList<>();
            int alreadyPending = 0;
            boolean hasUnknown = false;
            for (RecoveryRow row : rows) {
                if (row.deliveredAt() == null) {
                    alreadyPending++;
                    continue;
                }
                try {
                    var receipt = MAPPER.readTree(row.json());
                    String status = receipt.path("status").asText();
                    boolean retryable = receipt.path("retryable").asBoolean(false);
                    if ("unknown".equals(status)) hasUnknown = true;
                    if ("unknown".equals(status) || ("failed".equals(status) && !retryable)) {
                        eligible.add(new RecoveryItem(row.channelId(), status,
                                receipt.path("channel").asText(row.channelId()),
                                receipt.path("type").asText("UNKNOWN"),
                                receipt.path("errorCode").asText(null), row.generation() + 1));
                    }
                } catch (Exception invalid) {
                    throw ApiException.of(409, "Notification receipt is invalid; inspect durable state before replay");
                }
            }
            if (hasUnknown && !confirmUnknown) {
                throw ApiException.of(409, "Unknown notification result requires confirmation that replay is safe");
            }
            for (RecoveryItem item : eligible) {
                jdbc.update("update t_notification_delivery set delivered_at = null, result_json = '{}', "
                                + "claim_token = null, next_attempt_at = current_timestamp, "
                                + "recovery_generation = ? where tenant_id = ? and alarm_id = ? and channel_id = ?",
                        item.recoveryGeneration(), tenant, alarmId, item.channelId());
            }
            return new Recovery(alarmId, reason.trim(), eligible, alreadyPending);
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

    public record Claim(String token, String receiptJson, int recoveryGeneration) { }
    public record Recovery(String alarmId, String reason, List<RecoveryItem> reset, int alreadyPending) { }
    public record RecoveryItem(String channelId, String previousStatus, String channelName,
                               String channelType, String errorCode, int recoveryGeneration) { }
    private record Receipt(Timestamp deliveredAt, String json, Timestamp nextAttempt, int recoveryGeneration) { }
    private record RecoveryRow(String id, String channelId, Timestamp deliveredAt, String json, int generation) { }
}
