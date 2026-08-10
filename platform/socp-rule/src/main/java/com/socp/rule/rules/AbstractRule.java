package com.socp.rule.rules;

import com.socp.rule.model.Alert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则基类：统一管理 id/name、待分发告警队列与命中统计（2026-08-10 新增）。
 * 统计用于规则健康度观测：命中次数 / 累计告警数（Detection Engineering 基本能力）。
 */
public abstract class AbstractRule implements Rule {

    protected final String id;
    protected final String name;
    private final ArrayDeque<Alert> pending = new ArrayDeque<>();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong alertCount = new AtomicLong();

    protected AbstractRule(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    /** 子类命中时调用，把告警放入待分发队列并累计命中统计 */
    protected void emit(Alert alert) {
        hitCount.incrementAndGet();
        pending.add(alert);
    }

    @Override
    public List<Alert> drain() {
        List<Alert> out = new ArrayList<>(pending);
        pending.clear();
        alertCount.addAndGet(out.size());
        return out;
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("hits", hitCount.get());
        m.put("alerts", alertCount.get());
        return m;
    }
}
