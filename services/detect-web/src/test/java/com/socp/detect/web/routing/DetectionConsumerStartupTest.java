package com.socp.detect.web.routing;

import com.socp.detect.model.kafka.AlarmConsumer;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.detect.web.engine.KafkaEventConsumer;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.service.RuleChangeListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class DetectionConsumerStartupTest {
    @Test
    void consumersCannotReplayTransactionsWhileSingletonsAreStillBeingCreated() {
        var starts = new ArrayList<String>();
        var engine = mock(DetectEngineService.class);
        when(engine.assignedPartitions()).thenReturn(Set.of());
        var events = spy(new KafkaEventConsumer(engine));
        var changes = spy(new RuleChangeListener(engine));
        var alarms = spy(new AlarmConsumer(mock(AnalyzeService.class)));
        var routes = spy(new DetectionRouteSourceConsumer(mock(DetectionRouteOutboxService.class),
                "127.0.0.1:1", "events", "router", false));
        doAnswer(call -> { starts.add("events"); return call.callRealMethod(); }).when(events).start();
        doAnswer(call -> { starts.add("changes"); return call.callRealMethod(); }).when(changes).start();
        doAnswer(call -> { starts.add("alarms"); return call.callRealMethod(); }).when(alarms).start();
        doAnswer(call -> { starts.add("routes"); return call.callRealMethod(); }).when(routes).start();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(
                    org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
            // Start methods remain real, but this wiring test does not contact Kafka.
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("socp.kafka.enabled", "false", "socp.detect.state.retention", "1h",
                            "socp.instance-id", "startup-test")));
            context.registerBean("routingRuntime", DetectionRoutingRuntime.class,
                    () -> mock(DetectionRoutingRuntime.class));
            context.registerBean("events", KafkaEventConsumer.class, () -> events);
            context.registerBean("changes", RuleChangeListener.class, () -> changes);
            context.registerBean("alarms", AlarmConsumer.class, () -> alarms);
            context.registerBean("routes", DetectionRouteSourceConsumer.class, () -> routes);
            context.registerBean("lateSingleton", Object.class, () -> {
                assertEquals(List.of(), starts, "a consumer started while singleton creation was in progress");
                return new Object();
            });
            context.refresh();
            assertEquals(List.of(), starts, "refresh must not start replay before application runners finish");
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                    context, Duration.ZERO));
            assertEquals(Set.of("events", "changes", "alarms", "routes"), Set.copyOf(starts));
            assertEquals(4, starts.size());
        }
    }
}
