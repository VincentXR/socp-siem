package com.socp.gateway.filter;

import com.socp.gateway.api.controller.AuthController;
import com.socp.platform.auth.security.InMemoryServiceNonceStore;
import com.socp.platform.auth.security.ServiceNonceStore;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Real sockets, real session validation, HMAC verification and nonce consumption. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "socp.security.jwt-secret=retry-session-secret-0123456789abcdef0123456789abcdef",
        "socp.auth.login-secret=retry-session-secret-0123456789abcdef0123456789abcdef",
        "socp.security.service-secret=retry-upstream-secret-0123456789abcdef",
        "socp.security.dev-bypass=false",
        "socp.oidc.state.backend=memory"
})
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class ReportArchiveRetryHttpTest {
    private static final String SECRET = "retry-upstream-secret-0123456789abcdef";
    private static final String PATH = "/report-web/api/v1/reports/archive";
    private static final CopyOnWriteArrayList<HttpHeaders> ATTEMPTS = new CopyOnWriteArrayList<>();
    private static final InMemoryServiceNonceStore NONCES = new InMemoryServiceNonceStore();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .handle((request, response) -> {
                var headers = new HttpHeaders();
                request.requestHeaders().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
                ATTEMPTS.add(headers);
                String bearer = headers.getFirst(HttpHeaders.AUTHORIZATION);
                String nonce = headers.getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER);
                boolean valid = bearer != null && bearer.startsWith("Bearer ")
                        && ServiceRequestSignature.verify(SECRET,
                        headers.getFirst(ServiceRequestSignature.GATEWAY_SIGNATURE_HEADER),
                        ServiceRequestSignature.GATEWAY_SERVICE, "GET",
                        ServiceRequestSignature.gatewayBinding(PATH, bearer.substring(7)), "tenant-a",
                        headers.getFirst(ServiceRequestSignature.GATEWAY_TIMESTAMP_HEADER), nonce);
                if (!valid || NONCES.claim(ServiceRequestSignature.GATEWAY_SERVICE, nonce, Duration.ofMinutes(2))
                        != ServiceNonceStore.ClaimResult.CLAIMED) {
                    return response.status(401).sendString(Mono.just("{\"code\":401,\"message\":\"Proof rejected\"}"));
                }
                if (ATTEMPTS.size() == 1) {
                    // Authentication succeeded and the proof was consumed; lose
                    // the TCP connection without sending an HTTP response.
                    response.withConnection(connection -> connection.dispose());
                    return Mono.empty();
                }
                return response.header("Content-Type", "application/json")
                        .sendString(Mono.just("{\"code\":0,\"data\":[],\"message\":\"ok\"}"));
            }).bindNow();

    @Autowired private WebTestClient client;
    @Autowired private AuthController auth;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("SOCP_SFM_URI", () -> "http://127.0.0.1:" + UPSTREAM.port());
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.disposeNow();
    }

    @Test
    void authenticatedDisconnectRetriesWithNewProofOverRealHttp() {
        String session = auth.sign("reader", "analyst", "tenant-a", "en-US");
        client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri(PATH + "?prefix=reports%2F").cookie(AuthController.SESSION_COOKIE, session)
                .header("X-Tenant-Id", "untrusted-tenant")
                .header(ServiceRequestSignature.GATEWAY_NONCE_HEADER, "untrusted-nonce")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.code").isEqualTo(0);
        assertThat(ATTEMPTS).hasSize(2);
        assertThat(ATTEMPTS.get(0).getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER))
                .isNotEqualTo(ATTEMPTS.get(1).getFirst(ServiceRequestSignature.GATEWAY_NONCE_HEADER));
        assertThat(ATTEMPTS).allSatisfy(headers -> {
            assertThat(headers.getFirst("X-Tenant-Id")).isEqualTo("tenant-a");
            assertThat(headers.getFirst("X-Socp-User")).isEqualTo("reader");
            assertThat(headers.getFirst(ServiceRequestSignature.GATEWAY_PATH_HEADER)).isEqualTo(PATH);
        });
    }
}
