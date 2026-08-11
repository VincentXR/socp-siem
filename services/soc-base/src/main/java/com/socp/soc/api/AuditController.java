package com.socp.soc.api;

import com.socp.platform.audit.AuditRecord;
import com.socp.platform.audit.AuditSink;
import com.socp.platform.auth.RequireRole;
import com.socp.soc.audit.AuditEntity;
import com.socp.soc.audit.AuditRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询 API：优先读持久化库（t_audit，Kafka 消费端 AuditConsumer 落库），
 * 库为空（未接 Kafka 的本地切片）时回退读内存 AuditSink——两条路径输出同构，
 * 前端/切片无需区分。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditSink sink;
    private final AuditRepository repository;

    public AuditController(AuditSink sink, AuditRepository repository) {
        this.sink = sink;
        this.repository = repository;
    }

    /** 最近审计记录（默认 50 条，支持 action 过滤）。 */
    @GetMapping("/records")
    @RequireRole({"admin", "analyst"})
    public Map<String, Object> records(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action) {
        int n = Math.min(Math.max(limit, 1), 500);
        if (pgAvailable()) {
            List<AuditEntity> all = repository.findAll(Sort.by(Sort.Direction.DESC, "ts"));
            List<Map<String, Object>> recs = new ArrayList<>();
            int taken = 0;
            for (AuditEntity e : all) {
                if (action != null && !action.isBlank() && !action.equals(e.getAction())) continue;
                recs.add(toMap(e));
                if (++taken >= n) break;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", repository.count());
            out.put("returned", recs.size());
            out.put("records", recs);
            return out;
        }
        List<AuditRecord> recs = sink.recent(n, action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", sink.size());
        out.put("returned", recs.size());
        out.put("records", recs);
        return out;
    }

    /** 审计统计：总条数 + 按 action / result 聚合。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Long> byAction = new LinkedHashMap<>();
        Map<String, Long> byResult = new LinkedHashMap<>();
        long total;
        if (pgAvailable()) {
            List<AuditEntity> all = repository.findAll();
            total = all.size();
            for (AuditEntity e : all) {
                byAction.merge(e.getAction(), 1L, Long::sum);
                byResult.merge(e.getResult(), 1L, Long::sum);
            }
        } else {
            List<AuditRecord> all = sink.recent(100_000, null);
            total = sink.size();
            for (AuditRecord r : all) {
                byAction.merge(r.action(), 1L, Long::sum);
                byResult.merge(r.result(), 1L, Long::sum);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byAction", byAction);
        out.put("byResult", byResult);
        return out;
    }

    /** 库是否已有落库数据（有 → 读库；无 → 回退内存 sink，本地切片不破）。 */
    private boolean pgAvailable() {
        try {
            return repository.count() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** AuditEntity → 与 AuditRecord 同构的 Map（前端无需感知数据源）。 */
    private static Map<String, Object> toMap(AuditEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenantId", e.getTenantId());
        m.put("action", e.getAction());
        m.put("operator", e.getOperator());
        m.put("target", e.getTarget());
        m.put("result", e.getResult());
        m.put("timestamp", e.getTs().toString());
        return m;
    }
}
