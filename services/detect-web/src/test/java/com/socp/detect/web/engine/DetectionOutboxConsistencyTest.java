package com.socp.detect.web.engine;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.detect.web.persistence.store.DetectionAlertOutboxService;
import com.socp.detect.web.service.EntityRiskStore;
import com.socp.detect.web.persistence.store.RuleSpecStore;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.config.Rules;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.Suppressor;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.score.RiskScorer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Cross-layer retry proof: failed outbox persistence releases suppression reservation. */
class DetectionOutboxConsistencyTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void failedOutboxWriteIsRetriedOnceAndOnlyThenVisibleInTheRecentView() throws Exception {
        DetectionAlertOutboxRepository repository = mock(DetectionAlertOutboxRepository.class);
        when(repository.existsByAlertIdAndTenantId(anyString(), anyString())).thenReturn(false);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                throw new IllegalStateException("outbox unavailable");
            }
            return invocation.getArgument(0, DetectionAlertOutboxEntity.class);
        }).when(repository).saveAndFlush(any(DetectionAlertOutboxEntity.class));

        RuleSpecStore ruleStore = mock(RuleSpecStore.class);
        EntityRiskStore riskStore = mock(EntityRiskStore.class);
        when(ruleStore.get("WEB-ATTACK")).thenReturn(Map.of());
        when(riskStore.recordForAlert(anyString(), anyString(), any(Severity.class), isNull(),
                anyString(), anyString(), anyInt()))
                .thenReturn(new RiskScorer.Score(65, "HIGH", Map.of()));
        AlertForwarder forwarder = new AlertForwarder(ruleStore, riskStore,
                new DetectionAlertOutboxService(repository));
        RecentAlertSink sink = new RecentAlertSink(10, forwarder, null);
        SecurityEvent event = new SecurityEvent("outbox-retry-event", Instant.now(), "web", "host-1",
                "SQLi attempt", Map.of("tenant_id", "tenant-a", "src_ip", "10.0.0.5"), Severity.HIGH);

        try (Suppressor suppressor = new Suppressor(Duration.ofMinutes(5));
             RuleEngine engine = new RuleEngine(Rules.defaultRules(), List.of(sink), suppressor)) {
            engine.start();
            assertThrows(ExecutionException.class,
                    () -> engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS));
            assertTrue(sink.recent().isEmpty(), "an alert is visible only after durable outbox persistence");

            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertEquals(1, sink.recent().size());

            // The successful retry commits suppression. A third replay is
            // suppressed and does not create another outbox row.
            engine.ingestAndAwait(event).get(3, TimeUnit.SECONDS);
            assertEquals(1, sink.recent().size());
            assertTrue(engine.suppressedCount() >= 1);
        }

        verify(repository, times(2)).saveAndFlush(any(DetectionAlertOutboxEntity.class));
    }
}
