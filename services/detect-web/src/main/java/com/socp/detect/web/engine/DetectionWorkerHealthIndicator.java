package com.socp.detect.web.engine;

import com.socp.detect.web.service.DetectEngineService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Includes actual recovery and poller liveness in the deployment health gate. */
@Component("detectionWorker")
public class DetectionWorkerHealthIndicator implements HealthIndicator {
    private final DetectEngineService engine;
    private final ObjectProvider<KafkaEventConsumer> consumers;

    public DetectionWorkerHealthIndicator(DetectEngineService engine,
                                          ObjectProvider<KafkaEventConsumer> consumers) {
        this.engine = engine;
        this.consumers = consumers;
    }

    @Override
    public Health health() {
        KafkaEventConsumer consumer = consumers.getIfAvailable();
        boolean pollerHealthy = consumer == null || consumer.isHealthy();
        boolean recovered = engine.isReady();
        return (recovered && pollerHealthy ? Health.up() : Health.down())
                .withDetail("recoveryStatus", engine.recoveryStatus().name())
                .withDetail("pollerHealthy", pollerHealthy)
                .build();
    }
}
