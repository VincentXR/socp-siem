package com.socp.alert;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 告警事件 Kafka 生产者（P3 Outbox）：把 outbox 发布的告警事件发到 `socp-alarm-events`，
 * 下游（CK 报表 / Incident / SOAR / Notify）消费。acks=all + 幂等 + 短 block 探测。
 */
@Component
public class AlertKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertKafkaPublisher.class);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-events}")
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
                    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** 发送一条告警事件（异步，key=alarmId）。失败打 WARN（outbox 保持 PENDING 由调度重发）。 */
    public void sendAlarmEvent(String alarmId, String payload) {
        if (!enabled || payload == null) return;
        try {
            producer().send(new ProducerRecord<>(topic, alarmId, payload),
                    (md, ex) -> {
                        if (ex != null) {
                            log.warn("告警事件发送失败 alarmId={}（outbox 将重试）: {}", alarmId, ex.getMessage());
                        }
                    });
        } catch (Exception ex) {
            log.warn("告警事件发送异常 alarmId={}: {}", alarmId, ex.getMessage());
        }
    }

    private volatile Boolean availableCache;
    private volatile long availableAt;

    /** Kafka broker 可达性（TCP 连接 bootstrap，5s 缓存）：不可达时 OutboxPublisher 暂缓发布（保留 PENDING）。
     *  不用 partitionsFor：metadata 缓存会误判 broker 断开后仍可达。 */
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
            log.warn("Kafka 探测不可达（outbox 暂缓发布）: {}", ex.getMessage());
            ok = false;
        }
        availableCache = ok;
        availableAt = System.currentTimeMillis();
        return ok;
    }
}
