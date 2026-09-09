package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuppressorTest {

    @Test
    void failedReservationLetsWaitingDeliveryProceed() throws Exception {
        Alert alert = new Alert("rule", "rule", Severity.INFO, "message", "entity", List.of());
        try (Suppressor suppressor = new Suppressor(Duration.ofHours(1))) {
            Suppressor.Batch first = suppressor.begin(List.of(alert));
            var entered = new java.util.concurrent.CountDownLatch(1);
            var retry = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                return suppressor.allow(alert);
            });
            try {
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> retry.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally { first.close(); }
            assertTrue(retry.get(2, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.jupiter.api.Assertions.assertFalse(suppressor.allow(alert));
        }
    }

    @Test
    void batchDeduplicatesKeysAndClosesIdempotently() {
        Alert alert = new Alert("rule", "rule", Severity.INFO, "message", "entity", List.of());
        try (Suppressor suppressor = new Suppressor(Duration.ofHours(1))) {
            Suppressor.Batch batch = suppressor.begin(List.of(alert, alert));
            org.junit.jupiter.api.Assertions.assertEquals(1, batch.alerts().size());
            batch.commit();
            batch.close();
            batch.close();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, batch::commit);
            org.junit.jupiter.api.Assertions.assertFalse(suppressor.allow(alert));
        }
    }

    @Test
    void boundsCardinalityWhenKeysAreHighCardinality() {
        try (Suppressor suppressor = new Suppressor(Duration.ofHours(1), 2)) {
            for (int i = 0; i < 20; i++) {
                suppressor.allow(new Alert("rule", "rule", Severity.INFO,
                        "message", "entity-" + i, List.of()));
            }

            assertTrue(suppressor.trackedKeys() <= 2);
        }
    }
}
