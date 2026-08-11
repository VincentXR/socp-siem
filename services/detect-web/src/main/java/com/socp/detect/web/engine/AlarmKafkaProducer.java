package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * 告警 Kafka 出口（DETECT MODEL 二次分析链路）：AlertForwarder 转发 ALERT 成功后，
 * 把原始告警异步发到 `socp-alarm-original` 主题，由 detect-model 消费做窗口聚合/二次关联。
 *
 * <p>best-effort：Kafka 不可用只打 WARN，不影响告警主链路（alert-web 落库仍在）。
 * 可靠性约定与事件总线一致：acks=all + 幂等 + 重试 + traceparent 透传（见 KafkaEventProducer）。
 */
@Component
public class AlarmKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(AlarmKafkaProducer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-original}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private volatile KafkaProducer<String, String> producer;

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
                    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
                    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
                    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** 异步发送原始告警（不阻塞转发热路径）；失败 WARN（可观测，不静默）。 */
    public void send(Map<String, Object> alarm, String alertId) {
        if (!enabled || alarm == null) return;
        String traceparent = com.socp.platform.obs.TraceIdFilter.buildTraceparent();
        Thread.startVirtualThread(() -> {
            try {
                String value = MAPPER.writeValueAsString(alarm);
                ProducerRecord<String, String> rec = new ProducerRecord<>(topic,
                        alertId == null ? "unknown" : alertId, value);
                if (traceparent != null) {
                    rec.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
                }
                producer().send(rec, (md, ex) -> {
                    if (ex != null) {
                        log.warn("告警 Kafka 发送失败 alertId={}（已触发重试）: {}", alertId, ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                log.warn("告警 Kafka 发送异常（降级，不影响 alert-web 落库）: {}", ex.getMessage());
            }
        });
    }
}
