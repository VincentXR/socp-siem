package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;

import java.util.List;

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

    @Override
    default void close() {
    }
}
