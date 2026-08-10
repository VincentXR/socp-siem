package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;

import java.util.List;
import java.util.Map;

/**
 * 规则抽象：消费安全事件，内部维护状态，命中时产出告警。
 * drain() 取走自上次调用以来累积的告警，交由引擎分发到各 sink。由 com.siem 迁移。
 */
public interface Rule extends AutoCloseable {

    String id();

    String name();

    /** 喂入一个事件 */
    void accept(SecurityEvent event);

    /** 取走新产生的告警（取走后清空内部缓冲） */
    List<Alert> drain();

    /** 规则命中统计（2026-08-10）：hits=命中次数，alerts=累计告警数 */
    default Map<String, Object> stats() {
        return Map.of("id", id(), "name", name(), "hits", 0L, "alerts", 0L);
    }

    @Override
    default void close() {
    }
}
