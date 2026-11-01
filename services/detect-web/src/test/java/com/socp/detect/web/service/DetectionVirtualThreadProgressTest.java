package com.socp.detect.web.service;

import com.socp.detect.web.persistence.repository.DetectionStateOwnerRepository;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.JpaDetectionStateOwnership;
import com.socp.rule.engine.EventAlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionVirtualThreadProgressTest {
    @Test
    void databaseWaitsYieldTheCarrierWhileStateAndOwnershipLocksAreHeld() throws Exception {
        Path output = Files.createTempFile("detection-carrier-", ".log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS),
                    "a pinned carrier prevents the simulated database completion from running");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (Exception failure) { return failure.toString(); }
            });
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(output);
        }
    }

    public static void main(String[] args) {
        EventAlertSink sink = new EventAlertSink() {
            @Override public void publish(SecurityEvent event, List<Alert> alerts) { awaitIo(); }
            @Override public void close() { }
        };
        try (RuleEngine engine = new RuleEngine(List.of(), List.of(sink))) {
            engine.start();
            engine.ingestAndAwait(new SecurityEvent("event", Instant.EPOCH, "test", "host", "raw",
                    Map.of("tenant_id", "test"), Severity.INFO)).join();
        }
        DetectionStateOwnerRepository repository = (DetectionStateOwnerRepository) Proxy.newProxyInstance(
                DetectionStateOwnerRepository.class.getClassLoader(),
                new Class<?>[]{DetectionStateOwnerRepository.class}, (proxy, method, arguments) -> {
                    awaitIo();
                    return method.getName().equals("claimExisting") ? 0 : 1;
                });
        var ownership = new JpaDetectionStateOwnership(repository, "test", Duration.ofSeconds(30));
        var recovery = new DetectionStateRecovery(new InMemoryDetectionStateStore(), null, ownership);
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                recovery.durableGuardFor(0, 0).run();
                recovery.durableGuardFor(0, 0).run();
                recovery.releaseAllStateLeases();
                done.complete(null);
            } catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.join();

        var rules = org.mockito.Mockito.mock(com.socp.detect.web.persistence.store.RuleSpecStore.class);
        org.mockito.Mockito.when(rules.list(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        var journal = (com.socp.detect.web.persistence.store.DetectionStateStore) Proxy.newProxyInstance(
                com.socp.detect.web.persistence.store.DetectionStateStore.class.getClassLoader(),
                new Class<?>[]{com.socp.detect.web.persistence.store.DetectionStateStore.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("replayRecentForTenant")) awaitIo();
                    return method.getReturnType() == boolean.class ? false : null;
                });
        var cold = new DetectionEngineLifecycle(rules,
                new com.socp.detect.web.engine.RecentAlertSink(1, null, null),
                com.socp.rule.engine.RuleProcessingObserver.NOOP,
                new DetectionStateRecovery(journal, null, DetectionStateOwnership.noop()));
        CompletableFuture<Void> restored = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                com.socp.platform.tenant.context.TenantContext.runWith("cold", () -> cold.engineFor("cold", 0));
                restored.complete(null);
            } catch (Throwable failure) { restored.completeExceptionally(failure); }
        });
        try { restored.join(); }
        finally { cold.stop(); }
    }

    private static void awaitIo() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        Thread.startVirtualThread(() -> ready.complete(null));
        ready.join();
    }
}
