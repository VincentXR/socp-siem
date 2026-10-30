package com.socp.platform.client.http;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.socp.platform.client.config.ServiceEndpoints;
import com.socp.platform.client.config.SocpClientProperties;
import com.sun.net.httpserver.HttpServer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTokenProviderTest {
    private static final Object PINNING_CONTROL = new Object();

    @Test
    void concurrentRefreshDoesNotPinVirtualCarriersAndInvalidationStillRefreshes(
            @TempDir Path temporary) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/service-token", exchange -> {
            int sequence = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(failure);
            }
            byte[] response = ("{\"token\":\"fixture-" + sequence + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var body = exchange.getResponseBody()) { body.write(response); }
        });
        server.start();
        try (var recording = new jdk.jfr.Recording();
             var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("fixture",
                    Map.of("socp.gateway.url", "http://127.0.0.1:" + server.getAddress().getPort())));
            var properties = new SocpClientProperties();
            properties.setRequestTimeoutMs(5000);
            var provider = new ServiceTokenProvider(new ServiceEndpoints(environment), properties,
                    "fixture-service", "fixture-secret");
            recording.enable("jdk.VirtualThreadPinned").withStackTrace().withThreshold(Duration.ZERO);
            recording.start();
            Thread.ofVirtual().name("token-pinning-control").start(() -> {
                synchronized (PINNING_CONTROL) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
                }
            }).join();
            var results = new ArrayList<CompletableFuture<String>>();
            for (int i = 0; i < 8; i++) results.add(CompletableFuture.supplyAsync(provider::token, workers));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS), "refresh request must reach the fixture");
                // Keep the real HTTP dependency pending long enough for JFR to
                // distinguish a parked virtual thread from a pinned carrier.
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
            } finally {
                release.countDown();
            }
            for (var result : results) assertEquals("fixture-1", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, requests.get(), "concurrent cache misses must share one exchange");
            provider.invalidate();
            assertEquals("fixture-2", workers.submit(provider::token).get(10, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
            recording.stop();
            var path = temporary.resolve("service-token.jfr");
            recording.dump(path);
            var pins = jdk.jfr.consumer.RecordingFile.readAllEvents(path);
            assertTrue(pins.stream().anyMatch(event -> "token-pinning-control".equals(event.getThread().getJavaName())));
            assertFalse(pins.stream().anyMatch(event -> event.getStackTrace() != null
                    && event.getStackTrace().getFrames().stream().anyMatch(frame ->
                    ServiceTokenProvider.class.getName().equals(frame.getMethod().getType().getName()))),
                    "Token exchange must release the Java 21 carrier while waiting for HTTP");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void parsesTokenAsJsonInsteadOfSubstringMatching() {
        assertEquals("signed-token", ServiceTokenProvider.extractToken(
                "{\"expiresIn\":300,\"token\":\"signed-token\"}"));
        assertNull(ServiceTokenProvider.extractToken("{\"message\":\"no token\"}"));
        assertNull(ServiceTokenProvider.extractToken("not-json"));
    }
}
