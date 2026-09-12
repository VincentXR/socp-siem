package com.socp.detect.web.engine;

import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.DetectionResult;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Recent alert view plus the durable Detection -> Alert hand-off. */
@Component
public class RecentAlertSink implements EventAlertSink {

    private static final int DEDUP_CAPACITY = 100_000;
    private final Deque<Alert> recent = new ArrayDeque<>();
    private final int capacity;
    private final AlertForwarder forwarder;
    private final AlertStreamHub streamHub;
    private final Set<String> publishedIds = new LinkedHashSet<>();
    private final Object viewLock = new Object();

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
        publish(event, alerts, null);
    }

    @Override
    public void publish(SecurityEvent event, List<Alert> alerts, Runnable durableCommitGuard) {
        List<Alert> safe = alerts == null ? List.of() : alerts;
        // Durable persistence is deliberately first. A failure propagates to
        // the Kafka completion future and prevents offset advancement.
        if (forwarder != null) {
            if (durableCommitGuard == null) forwarder.forwardAll(event, safe);
            else forwarder.forwardAll(event, safe, durableCommitGuard);
        } else if (durableCommitGuard != null) {
            durableCommitGuard.run();
        }
        for (Alert alert : safe) {
            if (alert == null || alert.id() == null || !remember(alert)) continue;
            if (streamHub != null) streamHub.broadcast(tenantOf(alert), alert);
        }
        if (forwarder == null && durableCommitGuard != null) durableCommitGuard.run();
    }

    /** Preserve the complete calculation envelope for the durable outbox. */
    @Override
    public void publish(DetectionResult result, Runnable durableCommitGuard) {
        if (result == null) throw new IllegalArgumentException("detection result is required");
        List<Alert> safe = result.alerts();
        if (forwarder != null) {
            forwarder.forward(result, durableCommitGuard);
        } else if (durableCommitGuard != null) {
            durableCommitGuard.run();
        }
        for (Alert alert : safe) {
            if (alert == null || alert.id() == null || !remember(alert)) continue;
            if (streamHub != null) streamHub.broadcast(tenantOf(alert), alert);
        }
        if (forwarder == null && durableCommitGuard != null) durableCommitGuard.run();
    }

    public List<Alert> recent() {
        synchronized (viewLock) {
            return List.copyOf(recent);
        }
    }

    public List<Alert> recent(String tenant) {
        synchronized (viewLock) {
            return recent.stream().filter(alert -> tenant.equals(tenantOf(alert))).toList();
        }
    }

    private static String tenantOf(Alert alert) {
        if (alert != null && alert.evidence() != null) {
            for (SecurityEvent event : alert.evidence()) {
                if (event != null) return event.tenantId();
            }
        }
        return "default";
    }

    private boolean remember(Alert alert) {
        synchronized (viewLock) {
            if (!publishedIds.add(alert.id())) return false;
            if (publishedIds.size() > DEDUP_CAPACITY) {
                Iterator<String> oldest = publishedIds.iterator();
                if (oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
            recent.addLast(alert);
            while (recent.size() > capacity) recent.removeFirst();
            return true;
        }
    }

    @Override
    public void close() {
        // Shared sink; the engine rebuild must not clear the view.
    }
}
