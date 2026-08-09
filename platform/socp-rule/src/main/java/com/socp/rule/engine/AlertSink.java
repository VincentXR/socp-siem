package com.socp.rule.engine;

import com.socp.rule.model.Alert;

/**
 * 告警出口：把规则产出的告警分发到不同目的地（控制台、文件、Kafka、Webhook…）。
 * 由 com.siem 迁移；DETECT 侧将实现 Kafka/PG sink。
 */
public interface AlertSink extends AutoCloseable {
    void publish(Alert alert);

    @Override
    void close();
}
