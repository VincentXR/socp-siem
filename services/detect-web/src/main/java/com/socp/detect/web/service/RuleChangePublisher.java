package com.socp.detect.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * 规则变更广播（2026-08-10）：规则 add/update/delete 后发 Kafka 消息到
 * {@code socp-rule-changes} topic，集群内其他 detect-web 实例监听后热更新引擎
 * （配合 DetectEngineService.reload() 的原子替换，实现"一处改规则、全集群生效"）。
 */
@Component
public class RuleChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(RuleChangePublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本实例唯一标识，用于接收端忽略自己发的消息（避免重复 reload） */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.rule-topic:socp-rule-changes}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private volatile KafkaProducer<String, String> producer;

    public String instanceId() {
        return instanceId;
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** 广播规则变更（fire-and-forget + 回调日志；Kafka 不可用不影响本地已生效的热更新） */
    public void publish(String ruleId, String action) {
        if (!enabled) return;
        try {
            String value = MAPPER.writeValueAsString(Map.of(
                    "ruleId", ruleId, "action", action,
                    "ts", Instant.now().toString(), "source", instanceId));
            producer().send(new ProducerRecord<>(topic, ruleId, value),
                    (md, ex) -> {
                        if (ex != null) log.warn("规则变更广播失败 ruleId={}: {}", ruleId, ex.getMessage());
                    });
        } catch (Exception ex) {
            log.warn("规则变更广播异常: {}", ex.getMessage());
        }
    }
}
