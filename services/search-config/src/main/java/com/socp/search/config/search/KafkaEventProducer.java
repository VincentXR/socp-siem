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
                    // 可靠性（2026-08-10）：acks=all + 幂等 + 重试，杜绝"发出去但丢了"的静默降级
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

    /** 异步发送一批事件（不阻塞采集热路径）；发送失败打 WARN（可观测，不再静默）。
     *  发送时透传 W3C traceparent（trace 上下文跨 Kafka 传播，见 detect-web 消费侧恢复 MDC）。 */
    public void sendEvents(List<SearchEvent> es) {
        if (!enabled || es == null || es.isEmpty()) return;
        String traceparent = com.socp.platform.obs.TraceIdFilter.buildTraceparent();
        Thread.startVirtualThread(() -> {
            try {
                KafkaProducer<String, String> p = producer();
                for (SearchEvent e : es) {
                    String value = MAPPER.writeValueAsString(e);
                    // key=eventId：同一事件进同一分区且顺序保证，配合幂等实现重试安全
                    var rec = new ProducerRecord<>(topic, e.eventId(), value);
                    if (traceparent != null) {
                        rec.headers().add("traceparent", traceparent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    p.send(rec,
                            (md, ex) -> {
                                if (ex != null) {
                                    log.warn("Kafka 发送失败 eventId={}（已触发重试）: {}", e.eventId(), ex.getMessage());
                                }
                            });
                }
            } catch (Exception ex) {
                log.warn("Kafka 发送异常（降级为 HTTP 直连兜底）: {}", ex.getMessage());
            }
        });
    }
}
