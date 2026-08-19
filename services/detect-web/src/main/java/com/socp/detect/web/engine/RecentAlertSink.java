package com.socp.detect.web.engine;

import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Recent alert view plus the durable Detection -> Alert hand-off. */
@Component
public class RecentAlertSink implements EventAlertSink {

    private final List<Alert> recent = new CopyOnWriteArrayList<>();
    private final int capacity;
    private final AlertForwarder forwarder;
    private final AlertStreamHub streamHub;
    private final Set<String> publishedIds = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public RecentAlertSink(AlertForwarder forwarder, AlertStreamHub streamHub) {
        this.capacity = 500;
        this.forwarder = forwarder;
        this.streamHub = streamHub;
    }

    public RecentAlertSink(int capacity, AlertForwarder forwarder, AlertStreamHub streamHub) {
        this.capacity = capacity;
        this.forwarder = forwarder;
        this.streamHub = streamHub;
    }

    @Override
    public void publish(Alert alert) {
        publish(null, alert == null ? List.of() : List.of(alert));
    }

    @Override
    public void publish(SecurityEvent event, List<Alert> alerts) {
        List<Alert> safe = alerts == null ? List.of() : alerts;
        // Durable persistence is deliberately first. A failure propagates to
        // the Kafka completion future and prevents offset advancement.
        if (forwarder != null) forwarder.forwardAll(event == null ? null : event.id(), safe);
        for (Alert alert : safe) {
            if (alert == null || alert.id() == null || !publishedIds.add(alert.id())) continue;
            recent.add(alert);
            int over = recent.size() - capacity;
            for (int i = 0; i < over && !recent.isEmpty(); i++) recent.remove(0);
            if (streamHub != null) streamHub.broadcast(alert);
        }
    }

    public List<Alert> recent() {
        return List.copyOf(recent);
    }

    @Override
    public void close() {
        // Shared sink; the engine rebuild must not clear the view.
    }
}
