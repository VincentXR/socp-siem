package com.socp.soc.service;

import com.socp.platform.audit.model.AuditRecord;
import com.socp.platform.audit.spi.AuditSink;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soc.persistence.entity.AuditEntity;
import com.socp.soc.persistence.repository.AuditRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped audit read use case. The controller stays transport-only while
 * this service owns the durable-store/fallback-store policy and projection.
 */
@Service
public class AuditQueryService {

    private final AuditSink sink;
    private final AuditRepository repository;

    public AuditQueryService(AuditSink sink, AuditRepository repository) {
        this.sink = sink;
        this.repository = repository;
    }

    public Map<String, Object> records(int limit, String action) {
        int n = Math.min(Math.max(limit, 1), 500);
        String tenant = TenantContext.require();
        if (pgAvailable(tenant)) {
            List<AuditEntity> all = repository.findTop500ByTenantIdOrderByTsDesc(tenant);
            List<Map<String, Object>> records = new ArrayList<>();
            for (AuditEntity entity : all) {
                if (action != null && !action.isBlank() && !action.equals(entity.getAction())) {
                    continue;
                }
                records.add(toMap(entity));
                if (records.size() >= n) {
                    break;
                }
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("total", repository.countByTenantId(tenant));
            output.put("returned", records.size());
            output.put("records", records);
            return output;
        }

        List<AuditRecord> records = sink.recent(tenant, n, action);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("total", sink.size(tenant));
        output.put("returned", records.size());
        output.put("records", records);
        return output;
    }

    public Map<String, Object> stats() {
        Map<String, Long> byAction = new LinkedHashMap<>();
        Map<String, Long> byResult = new LinkedHashMap<>();
        String tenant = TenantContext.require();
        long total;
        if (pgAvailable(tenant)) {
            List<AuditEntity> all = repository.findByTenantId(tenant);
            total = all.size();
            for (AuditEntity entity : all) {
                byAction.merge(entity.getAction(), 1L, Long::sum);
                byResult.merge(entity.getResult(), 1L, Long::sum);
            }
        } else {
            List<AuditRecord> all = sink.recent(tenant, 100_000, null);
            total = sink.size(tenant);
            for (AuditRecord record : all) {
                byAction.merge(record.action(), 1L, Long::sum);
                byResult.merge(record.result(), 1L, Long::sum);
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("total", total);
        output.put("byAction", byAction);
        output.put("byResult", byResult);
        return output;
    }

    private boolean pgAvailable(String tenant) {
        try {
            return repository.countByTenantId(tenant) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, Object> toMap(AuditEntity entity) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("eventId", entity.getEventId());
        output.put("tenantId", entity.getTenantId());
        output.put("action", entity.getAction());
        output.put("operator", entity.getOperator());
        output.put("target", entity.getTarget());
        output.put("result", entity.getResult());
        output.put("timestamp", entity.getTs().toString());
        return output;
    }
}
