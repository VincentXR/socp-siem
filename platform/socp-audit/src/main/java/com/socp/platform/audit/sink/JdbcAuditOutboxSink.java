package com.socp.platform.audit.sink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.TransactionalAuditSink;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Timestamp;
import java.util.Objects;

/** Persists an audit record in the owning service database; Kafka is a projection of this row. */
public final class JdbcAuditOutboxSink implements TransactionalAuditSink {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean postgres;

    public JdbcAuditOutboxSink(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    JdbcAuditOutboxSink(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        String product = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        this.postgres = "PostgreSQL".equals(product);
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
        String insert = """
                    INSERT INTO t_audit_outbox
                      (event_id, tenant_id, action, operator_id, target_name, result_text,
                       payload, status, attempt_count, next_attempt_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                    """;
        Object[] arguments = {
                    record.eventId(), record.tenantId(), record.action(), record.operator(),
                    record.target(), record.result(), payload,
                    Timestamp.from(record.timestamp()), Timestamp.from(record.timestamp())
        };
        if (postgres) {
            // A caught PostgreSQL unique-key exception still aborts the whole
            // transaction. The conflict-safe statement keeps an idempotent
            // audit replay from rolling back its business mutation.
            jdbc.update(insert + " ON CONFLICT (event_id) DO NOTHING", arguments);
            return;
        }
        try {
            jdbc.update(insert, arguments);
        } catch (DuplicateKeyException duplicate) {
            // H2/local compatibility. Production PostgreSQL never reaches
            // this exception path because it would mark the transaction
            // aborted even if the Java exception were caught.
        }
    }
}
