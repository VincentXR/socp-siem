package com.socp.soc.service;

import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soc.persistence.entity.AuditEntity;
import com.socp.soc.persistence.repository.AuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditQueryServiceTest {

    private final AuditSink sink = mock(AuditSink.class);
    private final AuditRepository repository = mock(AuditRepository.class);
    private final AuditQueryService service = new AuditQueryService(sink, repository);

    @BeforeEach
    void setTenant() {
        TenantContext.set("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void fallsBackToInMemorySinkWhenDatabaseHasNoRows() {
        AuditRecord record = new AuditRecord("tenant-a", "CREATE_IOC", "system", "threat",
                "SUCCESS", Instant.parse("2026-08-09T10:00:00Z"));
        when(repository.countByTenantId("tenant-a")).thenReturn(0L);
        when(sink.recent("tenant-a", 50, "CREATE")).thenReturn(List.of(record));
        when(sink.size("tenant-a")).thenReturn(1);

        Map<String, Object> result = service.records(50, "CREATE");

        assertThat(result).containsEntry("total", 1).containsEntry("returned", 1);
        assertThat(result.get("records")).isEqualTo(List.of(record));
    }

    @Test
    void readsDurableRowsOnlyForCurrentTenant() {
        AuditEntity entity = new AuditEntity("event-1", "tenant-a", "CREATE_IOC", "system", "threat",
                "SUCCESS", Instant.parse("2026-08-09T10:00:00Z"));
        when(repository.countByTenantId("tenant-a")).thenReturn(1L, 1L);
        when(repository.findTop500ByTenantIdOrderByTsDesc("tenant-a")).thenReturn(List.of(entity));

        Map<String, Object> result = service.records(50, null);

        assertThat(result).containsEntry("total", 1L).containsEntry("returned", 1);
        assertThat(result.get("records").toString()).contains("event-1", "tenant-a");
    }

    @Test
    void filtersDurableRowsAndHonorsTheRequestedLimit() {
        AuditEntity matching = new AuditEntity("event-2", "tenant-a", "DELETE_IOC", "alice", "ioc-2",
                "SUCCESS", Instant.parse("2026-08-09T11:00:00Z"));
        AuditEntity other = new AuditEntity("event-3", "tenant-a", "CREATE_IOC", "bob", "ioc-3",
                "SUCCESS", Instant.parse("2026-08-09T12:00:00Z"));
        when(repository.countByTenantId("tenant-a")).thenReturn(2L);
        when(repository.findTop500ByTenantIdOrderByTsDesc("tenant-a"))
                .thenReturn(List.of(other, matching));

        Map<String, Object> result = service.records(1, "DELETE_IOC");

        assertThat(result).containsEntry("total", 2L).containsEntry("returned", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) result.get("records");
        assertThat(records).hasSize(1);
        assertThat(records.get(0)).containsEntry("eventId", "event-2")
                .containsEntry("tenantId", "tenant-a")
                .containsEntry("action", "DELETE_IOC")
                .containsEntry("operator", "alice")
                .containsEntry("target", "ioc-2")
                .containsEntry("result", "SUCCESS")
                .containsEntry("timestamp", "2026-08-09T11:00:00Z");
    }

    @Test
    void aggregatesDurableRowsByActionAndResult() {
        AuditEntity first = new AuditEntity("event-4", "tenant-a", "CREATE_IOC", "alice", "ioc-4",
                "SUCCESS", Instant.now());
        AuditEntity second = new AuditEntity("event-5", "tenant-a", "CREATE_IOC", "bob", "ioc-5",
                "FAILURE", Instant.now());
        when(repository.countByTenantId("tenant-a")).thenReturn(2L);
        when(repository.findByTenantId("tenant-a")).thenReturn(List.of(first, second));

        Map<String, Object> result = service.stats();

        assertThat(result).containsEntry("total", 2L);
        assertThat(result.get("byAction")).isEqualTo(Map.of("CREATE_IOC", 2L));
        assertThat(result.get("byResult")).isEqualTo(Map.of("SUCCESS", 1L, "FAILURE", 1L));
    }

    @Test
    void fallsBackToInMemoryStatsWhenDurableStoreIsUnavailable() {
        AuditRecord first = new AuditRecord("tenant-a", "CREATE_IOC", "system", "threat",
                "SUCCESS", Instant.now());
        AuditRecord second = new AuditRecord("tenant-a", "CREATE_IOC", "system", "threat",
                "FAILURE", Instant.now());
        when(repository.countByTenantId("tenant-a")).thenThrow(new IllegalStateException("database unavailable"));
        when(sink.recent("tenant-a", 100_000, null)).thenReturn(List.of(first, second));
        when(sink.size("tenant-a")).thenReturn(2);

        Map<String, Object> result = service.stats();

        assertThat(result).containsEntry("total", 2L);
        assertThat(result.get("byAction")).isEqualTo(Map.of("CREATE_IOC", 2L));
        assertThat(result.get("byResult")).isEqualTo(Map.of("SUCCESS", 1L, "FAILURE", 1L));
    }
}
