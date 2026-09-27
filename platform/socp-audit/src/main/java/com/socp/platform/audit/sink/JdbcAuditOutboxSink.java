package com.socp.platform.audit.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.TransactionalAuditSink;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.Objects;

/** Persists an audit record in the owning service database; Kafka is a projection of this row. */
public final class JdbcAuditOutboxSink implements TransactionalAuditSink {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAuditOutboxSink(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    JdbcAuditOutboxSink(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void publish(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        final String payload;
        try {
            payload = mapper.writeValueAsString(record);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot serialize audit record", failure);
        }
        try {
            jdbc.update("""
                    INSERT INTO t_audit_outbox
                      (event_id, tenant_id, action, operator_id, target_name, result_text,
                       payload, status, attempt_count, next_attempt_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """,
                    record.eventId(), record.tenantId(), record.action(), record.operator(),
                    record.target(), record.result(), payload,
                    Timestamp.from(record.timestamp()), Timestamp.from(record.timestamp()));
        } catch (DuplicateKeyException duplicate) {
            // AuditRecord IDs are stable across publisher replay. A duplicate
            // insert is therefore the same durable audit fact, not a new one.
        }
    }
}
