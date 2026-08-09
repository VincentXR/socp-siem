package com.socp.soar.web.api;

import com.socp.soar.web.model.Playbook;
import com.socp.soar.web.store.PlaybookStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SOAR 剧本 API：CRUD + 启停。
 * 当前为内存态执行（actions 以日志输出），Temporal Saga 接线后转为 Workflow 执行。
 */
@RestController
@RequestMapping("/api/v1/playbooks")
public class PlaybookController {

    private final PlaybookStore store;
    private final com.socp.soar.web.service.PlaybookExecutor executor;

    public PlaybookController(PlaybookStore store, com.socp.soar.web.service.PlaybookExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    @GetMapping
    public List<Playbook> list() {
        return store.list();
    }

    @PostMapping
    public Playbook create(@RequestBody CreatePlaybookRequest req) {
        Playbook pb = Playbook.create(req.name(), req.trigger(), req.actions(), req.enabled());
        return store.save(pb);
    }

    @GetMapping("/{id}")
    public Playbook get(@PathVariable String id) {
        return store.get(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return Map.of("removed", store.delete(id));
    }

    @PostMapping("/{id}/toggle")
    public Playbook toggle(@PathVariable String id) {
        return store.toggle(id);
    }

    /** 评估并编排执行：接收告警，命中启用的剧本触发条件则执行动作。 */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestBody Map<String, Object> alarm) {
        return executor.evaluate(alarm);
    }

    /** 手动触发指定剧本执行（忽略触发条件，直接跑 actions）。 */
    @PostMapping("/{id}/execute")
    public Map<String, Object> execute(@PathVariable String id, @RequestBody(required = false) Map<String, Object> context) {
        return executor.runById(id, context == null ? Map.of() : context);
    }

    /** 执行历史（最近 200 条）。 */
    @GetMapping("/executions")
    public List<Map<String, Object>> executions() {
        return executor.executions();
    }

    public record CreatePlaybookRequest(
            String name,
            String trigger,
            List<String> actions,
            boolean enabled
    ) {
    }
}
