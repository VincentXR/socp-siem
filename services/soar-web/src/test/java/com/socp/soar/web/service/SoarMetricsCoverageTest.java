package com.socp.soar.web.service;

import com.socp.soar.web.persistence.repository.SoarDispatchOutboxRepository;
import com.socp.soar.web.persistence.repository.SoarRunRepository;
import com.socp.soar.web.persistence.repository.SoarSignalOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/** Micrometer binding and counter coverage for the low-cardinality SOAR metrics. */
@ExtendWith(MockitoExtension.class)
class SoarMetricsCoverageTest {

    @Mock
    private SoarDispatchOutboxRepository dispatches;
    @Mock
    private SoarSignalOutboxRepository signals;
    @Mock
    private SoarRunRepository runs;

    private SimpleMeterRegistry registry;
    private SoarMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SoarMetrics(dispatches, signals, runs);
    }

    @Test
    void countersAreNoOpsBeforeBinding() {
        assertThatCode(() -> {
            metrics.triggerReceived();
            metrics.triggerSuppressed();
            metrics.actionUnknown();
            metrics.redaction();
        }).doesNotThrowAnyException();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void bindToRegistersBacklogGaugesActiveRunsAndCounters() {
        given(dispatches.countByStatus("PENDING")).willReturn(3L);
        given(signals.countByStatus("PENDING")).willReturn(5L);
        given(runs.countByStatus("RUNNING")).willReturn(1L);
        given(runs.countByStatus("WAITING_APPROVAL")).willReturn(2L);
        given(runs.countByStatus("WAITING_INPUT")).willReturn(3L);
        given(runs.countByStatus("DISPATCHING")).willReturn(4L);

        metrics.bindTo(registry);

        assertThat(registry.get("soar_dispatch_backlog").gauge().value()).isEqualTo(3.0d);
        assertThat(registry.get("soar_signal_backlog").gauge().value()).isEqualTo(5.0d);
        assertThat(registry.get("soar_runs_active").gauge().value()).isEqualTo(10.0d);

        metrics.triggerReceived();
        metrics.triggerSuppressed();
        metrics.actionUnknown();
        metrics.redaction();
        assertThat(registry.get("soar_trigger_received_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("soar_trigger_suppressed_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("soar_action_unknown_total").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("soar_redactions_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    void gaugesFallBackToZeroWhenRepositoriesFail() {
        given(dispatches.countByStatus("PENDING")).willThrow(new IllegalStateException("db down"));
        given(signals.countByStatus("PENDING")).willThrow(new IllegalStateException("db down"));
        given(runs.countByStatus(anyString())).willThrow(new IllegalStateException("db down"));

        metrics.bindTo(registry);

        assertThat(registry.get("soar_dispatch_backlog").gauge().value()).isEqualTo(0.0d);
        assertThat(registry.get("soar_signal_backlog").gauge().value()).isEqualTo(0.0d);
        assertThat(registry.get("soar_runs_active").gauge().value()).isEqualTo(0.0d);
    }
}
