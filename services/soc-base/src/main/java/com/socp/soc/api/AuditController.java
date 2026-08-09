package com.socp.soc.api;

import com.socp.platform.audit.AuditRecord;
import com.socp.platform.audit.AuditSink;
import com.socp.platform.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询 API：读取 AuditSink 中留痕的操作记录（内存实现返回真实数据）。
 *
 * <p>各服务带 {@code @AuditOperation} 的写操作都会在这里汇总结案；
 * 生产环境换成 Kafka sink 后，由独立消费者落库并提供同构查询。
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditSink sink;

    public AuditController(AuditSink sink) {
        this.sink = sink;
    }

    /** 最近审计记录（默认 50 条，支持 action 过滤）。 */
    @GetMapping("/records")
    @RequireRole({"admin", "analyst"})
    public Map<String, Object> records(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String action) {
        int n = Math.min(Math.max(limit, 1), 500);
        List<AuditRecord> recs = sink.recent(n, action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", sink.size());
        out.put("returned", recs.size());
        out.put("records", recs);
        return out;
    }

    /** 审计统计：总条数 + 按 action 聚合。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<AuditRecord> all = sink.recent(100_000, null);
        Map<String, Long> byAction = new LinkedHashMap<>();
        Map<String, Long> byResult = new LinkedHashMap<>();
        for (AuditRecord r : all) {
            byAction.merge(r.action(), 1L, Long::sum);
            byResult.merge(r.result(), 1L, Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", sink.size());
        out.put("byAction", byAction);
        out.put("byResult", byResult);
        return out;
    }
}
