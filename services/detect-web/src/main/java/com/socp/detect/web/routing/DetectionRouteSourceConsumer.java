package com.socp.detect.web.routing;

import com.socp.detect.web.config.DetectRuntimeRole;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes canonical socp-events only to transactionally persist route outbox
 * rows. No in-memory queue is a delivery boundary.
 */
@Component
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
public class DetectionRouteSourceConsumer {

    private static final Logger log = LoggerFactory.getLogger(DetectionRouteSourceConsumer.class);

    private final DetectionRouteOutboxService routes;
    private final String bootstrap;
    private final String sourceTopic;
    private final String groupId;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile KafkaConsumer<String, String> consumer;
    private Thread thread;

    public DetectionRouteSourceConsumer(
            DetectionRouteOutboxService routes,
            @Value("${socp.kafka.bootstrap:localhost:9092}") String bootstrap,
            @Value("${socp.detect.routing.source-topic:${socp.kafka.topic:socp-events}}") String sourceTopic,
            @Value("${socp.detect.routing.source-group-id:socp-detect-router-v2}") String groupId,
            @Value("${socp.detect.routing.publisher-enabled:false}") boolean enabled) {
        this.routes = routes;
        this.bootstrap = bootstrap;
        this.sourceTopic = sourceTopic;
        this.groupId = groupId;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled) return;
        running.set(true);
        thread = Thread.ofPlatform().name("detection-route-source").daemon(true).start(this::run);
    }

    private void run() {
        long restartDelay = 250L;
        while (running.get()) {
            try (KafkaConsumer<String, String> current = new KafkaConsumer<>(properties())) {
                consumer = current;
                current.subscribe(java.util.List.of(sourceTopic));
                restartDelay = 250L;
                while (running.get()) {
                    var records = current.poll(Duration.ofMillis(500));
                    for (var record : records) {
                        routes.route(record.topic(), record.partition(), record.offset(), record.value());
                        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                        current.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                    }
                }
            } catch (WakeupException stopping) {
                if (running.get()) log.warn("Detection route source consumer woke unexpectedly");
            } catch (DetectionRoutingPlan.UnsupportedRoutingPlanException incompatible) {
                // Fail closed: the source offset is intentionally not committed.
                log.error("Detection routing plan is unsupported; canonical source remains pending: {}",
                        incompatible.getMessage());
                sleep(restartDelay);
                restartDelay = Math.min(30_000L, restartDelay * 2);
            } catch (RuntimeException failure) {
                log.warn("Detection route source session failed; uncommitted source will replay: {}",
                        failure.getMessage());
                sleep(restartDelay);
                restartDelay = Math.min(30_000L, restartDelay * 2);
            } finally {
                consumer = null;
            }
        }
    }

    private Properties properties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        return props;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        KafkaConsumer<String, String> current = consumer;
        if (current != null) current.wakeup();
        if (thread != null) thread.interrupt();
    }
}
