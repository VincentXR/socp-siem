package com.socp.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Outbox 发布器（P3）：定时扫描待发布（PENDING）的告警事件 → 发 Kafka socp-alarm-events → 标记 PUBLISHED。
 * 与 t_alarm 同事务写入的 outbox 保证「告警落库成功则事件必发」；Kafka 不可达时保留 PENDING 重试，
 * 下游（CK/Incident/SOAR/Notify）从 Kafka 消费，失败可重放。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepo;
    private final AlertKafkaPublisher kafkaPublisher;

    public OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
    }

    @Scheduled(fixedDelay = 2000)
    public void publish() {
        List<OutboxEvent> pending;
        try {
            pending = outboxRepo.findByStatusOrderByCreatedAtAsc("PENDING");
        } catch (Exception e) {
            log.warn("Outbox 查询失败（下轮重试）: {}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) return;
        if (!kafkaPublisher.isAvailable()) {
            log.warn("Kafka 不可达，outbox 暂缓发布（{} 条 PENDING 待发）", pending.size());
            return;
        }
        for (OutboxEvent e : pending) {
            if (!kafkaPublisher.sendAlarmEventAndAwait(e.getAggregateId(), e.getPayload())) {
                continue;
            }
            e.setStatus("PUBLISHED");
            e.setPublishedAt(Instant.now());
            try {
                outboxRepo.save(e);
            } catch (Exception ex) {
                log.warn("Outbox 标记 PUBLISHED 失败（下轮重试）id={}: {}", e.getId(), ex.getMessage());
            }
        }
    }
}
