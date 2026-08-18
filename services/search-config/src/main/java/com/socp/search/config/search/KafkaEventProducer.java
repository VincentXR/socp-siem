package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.rule.partition.DetectionRoutingKey;
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
 * 由 DETECT 和 OpenSearch 索引消费者分别处理。Kafka 不可用时由
 * IngestPipeline 选择 OpenSearch 直写降级；本类只负责发送和记录失败。
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
                    // 探测（partitionsFor）与发送的阻塞上限：broker 不可达时快速失败而非卡 60s
                    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** 异步发送一批事件（不阻塞采集热路径）；发送失败打 WARN（可观测，不再静默）。
     *  发送时透传 W3C traceparent（trace 上下文跨 Kafka 传播，见 detect-web 消费侧恢复 MDC）。 */
    public void sendEvents(List<SearchEvent> es) {        if (!enabled || es == null || es.isEmpty()) return;
        String traceparent = com.socp.platform.obs.TraceIdFilter.buildTraceparent();
        Thread.startVirtualThread(() -> {
            try {
                KafkaProducer<String, String> p = producer();
                for (SearchEvent e : es) {
                    String value = MAPPER.writeValueAsString(e);
                    // key=tenant + entity：同一状态实体进入同一分区，重试仍由 eventId 去重
                    String routingKey = DetectionRoutingKey.forSearchEvent(e.source(), e.host(), e.fields());
                    var rec = new ProducerRecord<>(topic, routingKey, value);
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
                log.warn("Kafka 发送异常，采集管线将按可用性选择 OpenSearch 降级路径: {}", ex.getMessage());
            }
        });
    }

    // ---- P2（2026-08-12）：Kafka 可用性探测 ----
    private volatile Boolean availableCache;
    private volatile long availableAt;

    /** Kafka broker 可达性（TCP 连接 bootstrap，5s 缓存）。不可达时采集管线降级直写 OpenSearch。
     *  不用 partitionsFor：metadata 有 5 分钟缓存，broker 断开后仍可能命中缓存误判为可达。 */
    public boolean isAvailable() {
        if (!enabled) return false;
        if (availableCache != null && System.currentTimeMillis() - availableAt < 5_000L) {
            return availableCache;
        }
        boolean ok = false;
        try {
            String hp = bootstrap.trim();
            int idx = hp.indexOf(':');
            String host = idx > 0 ? hp.substring(0, idx) : hp;
            int port = idx > 0 ? Integer.parseInt(hp.substring(idx + 1)) : 9092;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), 2000);
                ok = true;
            }
        } catch (Exception ex) {
            log.warn("Kafka 探测不可达（降级直写 OpenSearch）: {}", ex.getMessage());
            ok = false;
        }
        availableCache = ok;
        availableAt = System.currentTimeMillis();
        return ok;
    }
}
