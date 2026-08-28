package com.socp.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void appliesSecurityHeadersAndOnlyEmitsHstsForHttps() {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockServerWebExchange https = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://socp.example.test/auth/session").build());

        filter.filter(https, exchange -> exchange.getResponse().setComplete()).block();

        assertThat(https.getResponse().getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
        assertThat(https.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(https.getResponse().getHeaders().getFirst("Strict-Transport-Security"))
                .contains("max-age=31536000");

        MockServerWebExchange http = MockServerWebExchange.from(MockServerHttpRequest.get("/health").build());
        filter.filter(http, ignored -> Mono.empty()).block();
        assertThat(http.getResponse().getHeaders()).doesNotContainKey("Strict-Transport-Security");
    }
}
