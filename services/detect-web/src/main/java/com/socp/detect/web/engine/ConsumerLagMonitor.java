package com.socp.detect.web.engine;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka 消费 lag 监控（2026-08-10）：周期性查询 socp-detect consumer group 在 socp-events
 * 各分区的 lag（end offset - committed offset），暴露 Micrometer Gauge：
 * {@code socp_kafka_consumer_lag{partition="0"}} 供 Prometheus/Grafana 告警。
 * lag 持续增长 = 消费跟不上生产，需扩容消费者或优化处理。
 */
@Component
public class ConsumerLagMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagMonitor.class);
    private static final String GROUP = "socp-detect";

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    private final MeterRegistry registry;
    private final Map<Integer, AtomicLong> lagHolders = new ConcurrentHashMap<>();

    public ConsumerLagMonitor(MeterRegistry registry) {
        this.registry = registry;
    }

    private KafkaConsumer<String, String> client() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    private AdminClient adminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        return AdminClient.create(props);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void reportLag() {
        try (KafkaConsumer<String, String> c = client(); AdminClient admin = adminClient()) {
            List<org.apache.kafka.common.PartitionInfo> parts = c.partitionsFor(topic, Duration.ofSeconds(5));
            if (parts == null || parts.isEmpty()) return;
            java.util.Set<TopicPartition> tps = new java.util.LinkedHashSet<>();
            for (var p : parts) tps.add(new TopicPartition(topic, p.partition()));

            // Java consumer 查询 committed offset 需先 assign（不参与消费，仅管理端查询）
            c.assign(tps);
            Map<TopicPartition, Long> ends = c.endOffsets(tps);
            // The AdminClient reads the production group's offsets without
            // adding a member, so lag reporting cannot trigger a rebalance.
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(GROUP)
                            .partitionsToOffsetAndMetadata()
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);

            for (TopicPartition tp : tps) {
                long end = ends.getOrDefault(tp, 0L);
                long comm = committed.get(tp) == null ? 0L : committed.get(tp).offset();
                long lag = Math.max(0L, end - comm);
                AtomicLong holder = lagHolders.computeIfAbsent(tp.partition(),
                        p -> {
                            AtomicLong h = new AtomicLong(0);
                            Gauge.builder("socp_kafka_consumer_lag", h, AtomicLong::get)
                                    .tag("topic", topic)
                                    .tag("group", GROUP)
                                    .tag("partition", String.valueOf(p))
                                    .register(registry);
                            return h;
                        });
                holder.set(lag);
            }
        } catch (Exception ex) {
            log.warn("Kafka lag 监控查询失败（Kafka 不可用时忽略）: {}", ex.getMessage());
        }
    }
}
