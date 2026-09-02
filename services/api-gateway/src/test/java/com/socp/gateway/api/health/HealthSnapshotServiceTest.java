package com.socp.gateway.api.health;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthSnapshotServiceTest {

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> requestPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            requestPath.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void probesDownstreamThroughRouteAndCachesTheSnapshot() {
        RouteLocator routes = mock(RouteLocator.class);
        URI target = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        Route alertRoute = Route.async()
                .id("alert-web")
                .uri(target)
                .predicate(exchange -> true)
                .build();
        when(routes.getRoutes()).thenReturn(Flux.just(alertRoute));

        HealthSnapshotService service = new HealthSnapshotService(
                routes, WebClient.builder(), (HealthEndpoint) null, 60_000, 1_000);

        HealthSnapshot first = service.snapshot().block(Duration.ofSeconds(5));
        HealthSnapshot second = service.snapshot().block(Duration.ofSeconds(5));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("up", first.services().get("alert-web"));
        assertEquals("up", first.services().get("api-gateway"));
        assertEquals("down", first.services().get("search-config"));
        assertEquals("down", first.status());
        assertEquals("/alert-web/actuator/health", requestPath.get());
        assertEquals(1, requestCount.get());
        assertEquals(first.checkedAt(), second.checkedAt());
    }
}
