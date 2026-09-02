package com.socp.gateway.api.health;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds one gateway-level health snapshot instead of making every browser
 * probe every downstream service independently.
 */
@Service
public class HealthSnapshotService {

    public static final List<String> SERVICE_NAMES = List.of(
            "alert-web", "search-config", "detect-web", "detect-model", "soar-web",
            "report-web", "asset-web", "soc-base", "hips-web", "ai-assistant",
            "threat-web", "attack-web", "notify-web", "incident-web", "api-gateway");

    private static final String GATEWAY = "api-gateway";
    private static final String UP = "up";
    private static final String DOWN = "down";

    private final RouteLocator routeLocator;
    private final WebClient webClient;
    private final HealthEndpoint gatewayHealth;
    private final long cacheTtlMs;
    private final Duration probeTimeout;
    private final AtomicReference<HealthSnapshot> cached = new AtomicReference<>();
    private final AtomicReference<Mono<HealthSnapshot>> refreshInFlight = new AtomicReference<>();

    @Autowired
    public HealthSnapshotService(
            RouteLocator routeLocator,
            WebClient.Builder webClientBuilder,
            ObjectProvider<HealthEndpoint> gatewayHealth,
            @Value("${socp.health.cache-ms:30000}") long cacheTtlMs,
            @Value("${socp.health.probe-timeout-ms:1000}") long probeTimeoutMs) {
        this(routeLocator, webClientBuilder, gatewayHealth.getIfAvailable(), cacheTtlMs, probeTimeoutMs);
    }

    HealthSnapshotService(
            RouteLocator routeLocator,
            WebClient.Builder webClientBuilder,
            HealthEndpoint gatewayHealth,
            long cacheTtlMs,
            long probeTimeoutMs) {
        this.routeLocator = routeLocator;
        this.webClient = webClientBuilder.build();
        this.gatewayHealth = gatewayHealth;
        this.cacheTtlMs = Math.max(1_000, cacheTtlMs);
        this.probeTimeout = Duration.ofMillis(Math.max(100, probeTimeoutMs));
    }

    /** Returns a cached snapshot and coalesces concurrent cache misses. */
    public Mono<HealthSnapshot> snapshot() {
        HealthSnapshot current = cached.get();
        if (isFresh(current)) return Mono.just(current);

        Mono<HealthSnapshot> ongoing = refreshInFlight.get();
        if (ongoing != null) return ongoing;

        Mono<HealthSnapshot> refresh = loadSnapshot()
                .doOnNext(cached::set)
                .onErrorResume(error -> {
                    HealthSnapshot stale = cached.get();
                    return stale == null ? Mono.error(error) : Mono.just(stale);
                })
                .doFinally(signal -> refreshInFlight.set(null))
                .cache();
        if (refreshInFlight.compareAndSet(null, refresh)) return refresh;
        return snapshot();
    }

    private Mono<HealthSnapshot> loadSnapshot() {
        return routeLocator.getRoutes()
                .collectMap(Route::getId, Route::getUri)
                .flatMap(routes -> gatewayStatus().flatMap(gatewayStatus ->
                        Flux.fromIterable(SERVICE_NAMES)
                                .flatMapSequential(name -> probe(name, gatewayStatus, routes), SERVICE_NAMES.size())
                                .collectList()
                                .map(results -> assemble(results))));
    }

    private Mono<ServiceResult> probe(String service, String gatewayStatus, Map<String, URI> routes) {
        if (GATEWAY.equals(service)) return Mono.just(new ServiceResult(service, gatewayStatus));

        URI routeUri = routes.get(service);
        if (routeUri == null) return Mono.just(new ServiceResult(service, DOWN));

        return webClient.get()
                .uri(healthUri(routeUri, service))
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().thenReturn(DOWN);
                    }
                    return response.bodyToMono(JsonNode.class)
                            .map(body -> body.path("status").asText("")
                                    .equalsIgnoreCase("UP") ? UP : DOWN)
                            .defaultIfEmpty(DOWN);
                })
                .timeout(probeTimeout)
                .onErrorReturn(DOWN)
                .map(status -> new ServiceResult(service, status));
    }

    private Mono<String> gatewayStatus() {
        if (gatewayHealth == null) return Mono.just(UP);
        return Mono.fromCallable(gatewayHealth::health)
                .subscribeOn(Schedulers.boundedElastic())
                .map(HealthComponent::getStatus)
                .map(status -> status.getCode().equalsIgnoreCase("UP") ? UP : DOWN)
                .onErrorReturn(DOWN);
    }

    private static HealthSnapshot assemble(List<ServiceResult> results) {
        Map<String, String> services = new LinkedHashMap<>();
        results.forEach(result -> services.put(result.name(), result.status()));
        boolean allUp = services.size() == SERVICE_NAMES.size()
                && services.values().stream().allMatch(UP::equals);
        return new HealthSnapshot(allUp ? UP : DOWN, services, Instant.now());
    }

    private boolean isFresh(HealthSnapshot snapshot) {
        return snapshot != null
                && snapshot.checkedAt().plusMillis(cacheTtlMs).isAfter(Instant.now());
    }

    private static URI healthUri(URI routeUri, String service) {
        String basePath = routeUri.getPath() == null ? "" : routeUri.getPath();
        String separator = basePath.endsWith("/") ? "" : "/";
        return UriComponentsBuilder.fromUri(routeUri)
                .replacePath(basePath + separator + service + "/actuator/health")
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUri();
    }

    private record ServiceResult(String name, String status) {
    }
}
