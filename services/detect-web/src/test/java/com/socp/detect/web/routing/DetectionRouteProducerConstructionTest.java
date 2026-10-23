package com.socp.detect.web.routing;

import com.socp.detect.web.persistence.repository.DetectionRouteOutboxRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class DetectionRouteProducerConstructionTest {
    @Test
    void productionProducerConfigurationPassesRealKafka4Construction() {
        var publisher = new DetectionRouteOutboxPublisher(mock(DetectionRouteOutboxRepository.class),
                "127.0.0.1:1", true, 0, "7d", 100, 1);
        // No outbox reads or sends: validate the actual lazy producer constructor.
        KafkaProducer<String, String> producer = ReflectionTestUtils.invokeMethod(publisher, "producer");
        assertNotNull(producer);
        producer.close(Duration.ZERO);
    }
}
