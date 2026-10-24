package com.socp.alert.service;

import com.socp.alert.config.AlertKafkaProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AlertKafkaPublisherConstructionTest {
    @Test
    void productionProducerConfigurationPassesRealKafka4Construction() {
        var properties = new AlertKafkaProperties();
        properties.setBootstrap("127.0.0.1:1");
        var transport = new AlertKafkaPublisher(properties);
        // Constructor validation is broker-independent; this test sends no records.
        KafkaProducer<String, String> producer = ReflectionTestUtils.invokeMethod(transport, "producer");
        assertNotNull(producer);
        producer.close(Duration.ZERO);
    }
}
