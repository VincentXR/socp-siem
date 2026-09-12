package com.socp.detect.web.engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlarmKafkaProducerTest {

    @Test
    void disabledProducerMustNotReportSuccessfulDelivery() {
        AlarmKafkaProducer producer = new AlarmKafkaProducer();
        ReflectionTestUtils.setField(producer, "enabled", false);

        assertFalse(producer.sendAndAwait(Map.of("id", "a-1"), "a-1"));
    }

    @Test
    void nullAlarmIsProgrammingErrorRatherThanSuccessfulDelivery() {
        AlarmKafkaProducer producer = new AlarmKafkaProducer();

        assertThrows(IllegalArgumentException.class, () -> producer.sendAndAwait(null, "a-1"));
    }
}
