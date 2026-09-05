package com.socp.soar.web.service;

import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/** Low-cardinality SOAR metrics; tenant/run identifiers are intentionally absent. */
@Component
public class SoarMetrics implements MeterBinder {
    private final SoarDispatchOutboxRepository dispatches;
    private final SoarSignalOutboxRepository signals;
    private final SoarRunRepository runs;
    private Counter triggerReceived;
    private Counter triggerSuppressed;
    private Counter actionUnknown;
    private Counter redactions;

    public SoarMetrics(SoarDispatchOutboxRepository dispatches,
                       SoarSignalOutboxRepository signals, SoarRunRepository runs) {
        this.dispatches = dispatches;
        this.signals = signals;
        this.runs = runs;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("soar_dispatch_backlog", dispatches, this::safeDispatchBacklog);
        registry.gauge("soar_signal_backlog", signals, this::safeSignalBacklog);
        registry.gauge("soar_runs_active", runs, this::safeActiveRuns);
        triggerReceived = registry.counter("soar_trigger_received_total");
        triggerSuppressed = registry.counter("soar_trigger_suppressed_total");
        actionUnknown = registry.counter("soar_action_unknown_total");
        redactions = registry.counter("soar_redactions_total");
    }

    public void triggerReceived() { if (triggerReceived != null) triggerReceived.increment(); }
    public void triggerSuppressed() { if (triggerSuppressed != null) triggerSuppressed.increment(); }
    public void actionUnknown() { if (actionUnknown != null) actionUnknown.increment(); }
    public void redaction() { if (redactions != null) redactions.increment(); }

    private double safeDispatchBacklog(SoarDispatchOutboxRepository ignored) {
        try { return dispatches.countByStatus("PENDING"); } catch (RuntimeException failure) { return 0; }
    }

    private double safeSignalBacklog(SoarSignalOutboxRepository ignored) {
        try { return signals.countByStatus("PENDING"); } catch (RuntimeException failure) { return 0; }
    }

    private double safeActiveRuns(SoarRunRepository ignored) {
        try {
            return runs.countByStatus("RUNNING") + runs.countByStatus("WAITING_APPROVAL")
                    + runs.countByStatus("WAITING_INPUT") + runs.countByStatus("DISPATCHING");
        } catch (RuntimeException failure) { return 0; }
    }
}
