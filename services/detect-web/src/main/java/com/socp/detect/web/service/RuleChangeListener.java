package com.socp.detect.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 规则变更监听（2026-08-10）：消费 {@code socp-rule-changes} topic，收到规则变更消息
 * 触发 DetectEngineService.reload()（忽略自己发布的 source，避免重复热更新）。
 */
@Component
public class RuleChangeListener {

    private static final Logger log = LoggerFactory.getLogger(RuleChangeListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DetectEngineService engineService;
    private final RuleChangePublisher publisher;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.rule-topic:socp-rule-changes}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    public RuleChangeListener(DetectEngineService engineService, RuleChangePublisher publisher) {
        this.engineService = engineService;
        this.publisher = publisher;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("rule-change-consumer").daemon(true).start(() -> run());
        log.info("规则热更新监听已启动 topic={}", topic);
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-rule-change");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(topic));
            while (true) {
                var recs = c.poll(Duration.ofMillis(500));
                for (var r : recs) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = MAPPER.readValue(r.value(), Map.class);
                        String source = String.valueOf(m.getOrDefault("source", ""));
                        if (source.equals(publisher.instanceId())) {
                            continue; // 自己发布的变更，本地已 reload
                        }
                        log.info("收到规则变更（ruleId={} action={}，来自 {}），热更新引擎",
                                m.get("ruleId"), m.get("action"), source);
                        engineService.reload();
                    } catch (Exception ex) {
                        log.warn("规则变更消息处理失败: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("规则变更监听退出: {}", e.getMessage());
        }
    }
}
