package com.socp.gateway.filter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportArchiveReadRetryGatewayFilterFactoryTest {

    private static final String ARCHIVE_PATH = "/report-web/api/v1/reports/archive";
    private final GatewayFilter filter = new ReportArchiveReadRetryGatewayFilterFactory()
            .apply(new ReportArchiveReadRetryGatewayFilterFactory.Config());

    @Test
    void retriesOnlyOnceAndResetsTheRoutingMarkerWithoutChangingVerifiedIdentity() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(ARCHIVE_PATH + "?prefix=reports%2F")
                .header(HttpHeaders.AUTHORIZATION, "Bearer verified-session")
                .header("X-Tenant-Id", "tenant-a").build());
        String originalQuery = exchange.getRequest().getURI().getRawQuery();
        var attempts = new AtomicInteger();

        filter.filter(exchange, request -> {
            if (attempts.incrementAndGet() == 1) {
                ServerWebExchangeUtils.setAlreadyRouted(request);
                return Mono.error(PrematureCloseException.TEST_EXCEPTION);
            }
            assertThat(ServerWebExchangeUtils.isAlreadyRouted(request)).isFalse();
            assertThat(request.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer verified-session");
            assertThat(request.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
            assertThat(request.getRequest().getURI().getRawQuery()).isEqualTo(originalQuery);
            return request.getResponse().setComplete();
        }).block();

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void propagatesASecondDisconnectWithoutUnboundedRetry() {
        var exchange = archiveRequest();
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(exchange, request -> {
            attempts.incrementAndGet();
            return Mono.error(PrematureCloseException.TEST_EXCEPTION);
        }).block()).hasRootCauseInstanceOf(PrematureCloseException.class);
        assertThat(attempts.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void neverReplaysArchiveWritesOrOtherMethods(String method) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), ARCHIVE_PATH));
        assertNoRetry(exchange);
    }

    @Test
    void doesNotRetryOtherReportEndpoints() {
        assertNoRetry(MockServerWebExchange.from(MockServerHttpRequest.get("/report-web/api/v1/reports/daily")));
    }

    @Test
    void doesNotReplayAGetRequestWithABody() {
        assertNoRetry(MockServerWebExchange.from(MockServerHttpRequest.get(ARCHIVE_PATH)
                .header(HttpHeaders.CONTENT_LENGTH, "1")));
        assertNoRetry(MockServerWebExchange.from(MockServerHttpRequest.get(ARCHIVE_PATH)
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")));
    }

    @Test
    void doesNotSpliceResponsesAfterUpstreamHeadersWereReceived() {
        var exchange = archiveRequest();
        exchange.getAttributes().put(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR, new Object());
        assertNoRetry(exchange);
    }

    @Test
    void doesNotRetryACommittedBrowserResponse() {
        var exchange = archiveRequest();
        exchange.getResponse().setComplete().block();
        assertNoRetry(exchange);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    void preservesUpstreamHttpErrorsWithoutRetry(int status) {
        var exchange = archiveRequest();
        var attempts = new AtomicInteger();
        filter.filter(exchange, request -> {
            attempts.incrementAndGet();
            request.getResponse().setStatusCode(HttpStatus.valueOf(status));
            return request.getResponse().setComplete();
        }).block();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
    }

    @Test
    void preservesUnrelatedTransportFailures() {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(archiveRequest(), request -> {
            attempts.incrementAndGet();
            return Mono.error(new IOException("unrelated transport failure"));
        }).block()).hasRootCauseMessage("unrelated transport failure");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void shippedConfigurationBindsThePoolLimitsAndOnlyReportRouteEnablesRecovery() throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("gateway", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var binder = Binder.get(environment);
        var http = binder.bind("spring.cloud.gateway.server.webflux.httpclient", Bindable.of(HttpClientProperties.class)).get();
        assertThat(http.getPool().getMaxIdleTime()).isEqualTo(Duration.ofSeconds(10));
        assertThat(http.getPool().getEvictionInterval()).isEqualTo(Duration.ofSeconds(5));
        var routes = binder.bind("spring.cloud.gateway.server.webflux", Bindable.of(GatewayProperties.class)).get().getRoutes();
        assertThat(routes.stream().filter(route -> route.getFilters().stream()
                .anyMatch(config -> config.getName().equals("ReportArchiveReadRetry")))
                .map(route -> route.getId())).containsExactly("report-web");
    }

    private MockServerWebExchange archiveRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.get(ARCHIVE_PATH));
    }

    private void assertNoRetry(MockServerWebExchange exchange) {
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> filter.filter(exchange, request -> {
            attempts.incrementAndGet();
            return Mono.error(PrematureCloseException.TEST_EXCEPTION);
        }).block()).hasRootCauseInstanceOf(PrematureCloseException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }
}
