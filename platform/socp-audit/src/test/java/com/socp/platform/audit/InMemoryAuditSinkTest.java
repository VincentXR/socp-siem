package com.socp.platform.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAuditSinkTest {

    @Test
    void returnsNewestTenantScopedAndActionFilteredRecords() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.publish(new AuditRecord("e1", "tenant-a", "CREATE_RULE", "alice", "rule", "SUCCESS", Instant.EPOCH));
        sink.publish(new AuditRecord("e2", "tenant-b", "DELETE_RULE", "bob", "rule", "SUCCESS", Instant.EPOCH));
        sink.publish(new AuditRecord("e3", "tenant-a", "DELETE_RULE", "alice", "rule", "FAILED", Instant.EPOCH));

        assertThat(sink.size()).isEqualTo(3);
        assertThat(sink.size("tenant-a")).isEqualTo(2);
        assertThat(sink.recent("tenant-a", 1, "DELETE_RULE")).extracting(AuditRecord::eventId)
                .containsExactly("e3");
        assertThat(sink.recent(10, "CREATE")).extracting(AuditRecord::eventId)
                .containsExactly("e1");
    }
}
