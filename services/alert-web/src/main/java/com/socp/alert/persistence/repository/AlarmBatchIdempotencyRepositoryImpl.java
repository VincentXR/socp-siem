package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmBatchIdempotency;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

public class AlarmBatchIdempotencyRepositoryImpl implements AlarmBatchIdempotencyRepositoryCustom {
    private final JdbcTemplate jdbc;

    public AlarmBatchIdempotencyRepositoryImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean claim(AlarmBatchIdempotency row) {
        String tenant = TenantContext.require();
        if (!tenant.equals(row.getTenantId())) throw new IllegalArgumentException("batch tenant mismatch");
        Instant now = Instant.now();
        return ConflictSafeInsert.insert(jdbc, "t_alarm_batch_idempotency", "uq_alarm_batch_idempotency_tenant_key",
                List.of("id", "tenant_id", "idempotency_key", "request_hash", "response_json", "created_at", "updated_at"),
                List.of("tenant_id", "idempotency_key"), row.getId(), tenant, row.getIdempotencyKey(),
                row.getRequestHash(), "{}", now, now);
    }
}
