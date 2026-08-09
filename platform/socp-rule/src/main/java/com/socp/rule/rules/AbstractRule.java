package com.socp.rule.rules;

import com.socp.rule.model.Alert;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则基类：统一管理 id/name 与待分发告警队列。由 com.siem 迁移。
 */
public abstract class AbstractRule implements Rule {

    protected final String id;
    protected final String name;
    private final ArrayDeque<Alert> pending = new ArrayDeque<>();

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

    /** 子类命中时调用，把告警放入待分发队列 */
    protected void emit(Alert alert) {
        pending.add(alert);
    }

    @Override
    public List<Alert> drain() {
        List<Alert> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }
}
