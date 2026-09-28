package com.socp.platform.audit.sink;

import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

/**
 * Runtime-selected Kafka audit path.
 *
 * <p>Spring configuration conditions are evaluated while bean definitions are
 * still being discovered, so testing for a {@code JdbcTemplate} there can
 * incorrectly select direct Kafka in a database-backed service. This holder is
 * created after all definitions exist and keeps the selected sink and its
 * optional outbox publisher together.</p>
 */
public class AuditKafkaRuntime {

    private final AuditSink sink;
    private final AuditOutboxPublisher outboxPublisher;

    public AuditKafkaRuntime(AuditSink sink, AuditOutboxPublisher outboxPublisher) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.outboxPublisher = outboxPublisher;
    }

    public AuditSink sink() {
        return sink;
    }

    public boolean durableOutboxEnabled() {
        return outboxPublisher != null;
    }

    @Scheduled(fixedDelayString = "${socp.audit.outbox.poll-ms:1000}",
            initialDelayString = "${socp.audit.outbox.initial-delay-ms:1000}")
    @TenantSystemJob
    public void publishPending() {
        if (outboxPublisher != null) outboxPublisher.publishPending();
    }
}
