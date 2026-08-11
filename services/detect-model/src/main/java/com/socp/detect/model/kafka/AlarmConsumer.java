package com.socp.detect.model.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.model.service.AnalyzeService;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 告警 Kafka 消费者（DETECT MODEL 二次分析链路）：订阅 DETECT 转发的 `socp-alarm-original`
 * 主题，把原始告警交给 {@link AnalyzeService} 做窗口聚合/二次关联——与 HTTP /analyze 同一路径。
 *
 * <p>可靠性约定与事件总线一致：手动 commit（处理完才提交，重启最多重放一批）+ LRU 幂等去重
 * （配合至少一次语义）+ 解析/处理失败写 DLQ（socp-alarm-original-dlq），不静默丢弃。
 */
@Component
public class AlarmConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEDUP_MAX = 100_000;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-original}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final AnalyzeService analyzeService;

    public AlarmConsumer(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    /** 已处理 alertId 去重缓存（LRU：满则清空重建，配合至少一次语义实现幂等） */
    private static final Set<String> DEDUP = Collections.synchronizedSet(new LinkedHashSet<>() {
        @Override
        public boolean add(String e) {
            if (size() >= DEDUP_MAX) clear();
            return super.add(e);
        }
    });

    private volatile KafkaProducer<String, String> dlqProducer;

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> p = dlqProducer;
        if (p == null) {
            synchronized (this) {
                if (dlqProducer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    dlqProducer = new KafkaProducer<>(props);
                }
                p = dlqProducer;
            }
        }
        return p;
    }

    /** 处理失败 → 写入 DLQ 主题，不静默丢弃 */
    private void toDlq(String alertId, String raw) {
        try {
            dlq().send(new ProducerRecord<>(topic + "-dlq",
                    alertId == null ? "unknown" : alertId, raw));
        } catch (Exception ex) {
            log.warn("DLQ 写入失败 alertId={}: {}", alertId, ex.getMessage());
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("alarm-consumer").daemon(true).start(() -> run());
        log.info("告警 Kafka 消费者已启动 bootstrap={} topic={}", bootstrap, topic);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-detect-model");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        try {
            consumer.subscribe(List.of(topic));
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                } catch (Exception ex) {
                    log.warn("告警 Kafka poll 异常（重试）: {}", ex.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (records.isEmpty()) continue;
                for (ConsumerRecord<String, String> rec : records) {
                    String alertId = rec.key();
                    try {
                        if (alertId != null && !DEDUP.add(alertId)) continue; // 幂等去重
                        @SuppressWarnings("unchecked")
                        Map<String, Object> alarm = MAPPER.readValue(rec.value(), Map.class);
                        analyzeService.analyze(alarm);
                    } catch (Exception ex) {
                        log.warn("告警二次分析失败 alertId={}（写 DLQ）: {}", alertId, ex.getMessage());
                        toDlq(alertId, rec.value());
                    }
                }
                try {
                    consumer.commitSync();
                } catch (Exception ex) {
                    log.warn("告警 offset 提交失败（下次重放一批）: {}", ex.getMessage());
                }
            }
        } finally {
            try { consumer.close(); } catch (Exception ignored) { }
        }
    }
}
