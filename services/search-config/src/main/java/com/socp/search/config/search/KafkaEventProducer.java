package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;

/**
 * Kafka 事件生产者（事件总线接线）：把归一化事件异步发到 `socp-events` 主题，
 * DETECT 检测引擎消费后进规则引擎。best-effort：Kafka 不可用时静默降级
 * （HTTP 直连转发仍保留为兜底路径）。
 */
@Component
public class KafkaEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventProducer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
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
                    props.put(ProducerConfig.ACKS_CONFIG, "0");
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** 异步发送一批事件（不阻塞采集热路径） */
    public void sendEvents(List<SearchEvent> es) {
        if (!enabled || es == null || es.isEmpty()) return;
        Thread.startVirtualThread(() -> {
            try {
                KafkaProducer<String, String> p = producer();
                for (SearchEvent e : es) {
                    p.send(new ProducerRecord<>(topic, e.source(), MAPPER.writeValueAsString(e)));
                }
            } catch (Exception ex) {
                log.debug("Kafka 发送异常（静默降级）: {}", ex.getMessage());
            }
        });
    }
}
