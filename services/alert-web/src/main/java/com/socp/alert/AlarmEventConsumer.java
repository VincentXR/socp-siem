package com.socp.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SoarClient;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 告警事件消费者（P3 Outbox 下游）：消费 `socp-alarm-events`，执行
 * CK 报表写入 + Notify / Incident / SOAR 联动。与 t_alarm 解耦——告警创建后先落 PG + outbox，
 * 本消费者负责扇出，下游失败可从 Kafka 重放（不再依赖告警服务直调各下游的一致性）。
 *
 * <p>可靠性对齐 detect-web 消费者：手动 commit（至少一次）+ LRU 去重（幂等）+ 失败写 DLQ + traceparent 透传。
 */
@Component
public class AlarmEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final CkReporter ckReporter;
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SoarClient soarClient;

    public AlarmEventConsumer(CkReporter ckReporter, NotifyClient notifyClient,
                              IncidentClient incidentClient, SoarClient soarClient) {
        this.ckReporter = ckReporter;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.soarClient = soarClient;
    }

    private static final java.util.Set<String> DEDUP = java.util.Collections.synchronizedSet(
            new java.util.LinkedHashSet<>() {
                @Override
                public boolean add(String e) {
                    if (size() >= 100_000) clear();
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

    private void toDlq(String alarmId, String raw) {
        try {
            dlq().send(new ProducerRecord<>(topic + "-dlq", alarmId == null ? "unknown" : alarmId, raw));
        } catch (Exception ex) {
            log.warn("DLQ 写入失败 alarmId={}: {}", alarmId, ex.getMessage());
        }
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("alarm-event-consumer").daemon(true).start(() -> run());
        log.info("告警事件消费者已启动 topic={}（P3 Outbox 下游）", topic);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-alarm-fanout");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(topic));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                for (var r : recs) {
                    String raw = r.value();
                    String alarmId = r.key();
                    String tp = null;
                    try {
                        var h = r.headers().lastHeader("traceparent");
                        if (h != null) tp = new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (Exception ignored) {
                    }
                    String restored = tp == null ? null : com.socp.platform.obs.TraceIdFilter.parseTraceId(tp);
                    if (restored != null) org.slf4j.MDC.put("traceId", restored);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = MAPPER.readValue(raw, Map.class);
                        if (alarmId == null || !DEDUP.add(alarmId)) {
                            continue;
                        }
                        fanOut(m);
                    } catch (Exception ex) {
                        log.warn("告警事件处理失败 → DLQ: {}", ex.getMessage());
                        toDlq(alarmId, raw);
                    } finally {
                        org.slf4j.MDC.remove("traceId");
                    }
                }
                if (!recs.isEmpty()) {
                    try {
                        c.commitSync();
                    } catch (Exception ex) {
                        log.warn("Kafka commit 失败（下轮重试）: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("告警事件消费者退出: {}", e.getMessage());
        }
    }

    /** 下游扇出：CK 报表 + Notify / Incident / SOAR。任一下游失败不影响其余（best-effort 但有 WARN）。 */
    private void fanOut(Map<String, Object> m) {
        String alarmId = String.valueOf(m.getOrDefault("id", "?"));
        Alarm a = toAlarm(m);
        try {
            ckReporter.reportAlarm(a);
        } catch (Exception e) {
            log.warn("告警 CK 写入异常 alarmId={}: {}", alarmId, e.getClass().getSimpleName());
        }
        String json = toJsonString(m);
        dispatch("notify-web", alarmId, () -> notifyClient.notifyAlert(json));
        dispatch("incident-web", alarmId, () -> incidentClient.createFromAlarm(json));
        dispatch("soar-web", alarmId, () -> soarClient.evaluate(json));
    }

    private void dispatch(String downstream, String alarmId, java.util.function.Supplier<ServiceCall> action) {
        try {
            ServiceCall call = action.get();
            if (!call.ok()) {
                log.warn("告警联动失败 downstream={} alarmId={} 原因={}", downstream, alarmId, call.failureReason());
            }
        } catch (Exception e) {
            log.warn("告警联动异常 downstream={} alarmId={} error={}", downstream, alarmId,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String toJsonString(Map<String, Object> m) {
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Alarm toAlarm(Map<String, Object> m) {
        Alarm a = new Alarm();
        a.setRuleId(str(m.get("ruleId")));
        a.setRuleName(str(m.get("ruleName")));
        try {
            a.setSeverity(m.get("severity") == null ? null : Severity.valueOf(String.valueOf(m.get("severity")).toUpperCase()));
        } catch (IllegalArgumentException ignored) {
        }
        a.setMessage(str(m.get("message")));
        a.setEntity(str(m.get("entity")));
        a.setMitre(str(m.get("mitre")));
        a.setRiskScore(m.get("riskScore") instanceof Number n ? n.intValue() : null);
        a.setRiskLevel(str(m.get("riskLevel")));
        try {
            a.setOccurredAt(m.get("occurredAt") == null ? null : Instant.parse(String.valueOf(m.get("occurredAt"))));
        } catch (Exception ignored) {
        }
        return a;
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
