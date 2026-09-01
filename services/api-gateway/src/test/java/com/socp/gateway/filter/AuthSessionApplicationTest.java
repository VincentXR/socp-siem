package com.socp.gateway.filter;

import com.socp.gateway.api.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "socp.security.jwt-secret=test-session-jwt-secret-0123456789abcdef0123456789abcdef",
        "socp.auth.login-secret=test-session-jwt-secret-0123456789abcdef0123456789abcdef",
        "socp.security.service-secret=test-session-service-secret-0123456789",
        "socp.security.dev-bypass=false",
        "socp.oidc.state.backend=memory"
})
@AutoConfigureWebTestClient
@ActiveProfiles("dev")
class AuthSessionApplicationTest {

    @Autowired
    private WebTestClient client;

    @Test
    void rejectsMissingCredentialsAndForgedIdentityHeaders() {
        client.get().uri("/auth/session")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/auth/session")
                .headers(headers -> {
                    headers.set("X-Socp-User", "attacker");
                    headers.set("X-Socp-Role", "admin");
                    headers.set("X-Tenant-Id", "victim");
                })
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsInvalidSessionCookie() {
        client.get().uri("/auth/session")
                .cookie(AuthController.SESSION_COOKIE, "not-a-jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validSessionIgnoresForgedIdentityHeaders() {
        String setCookie = client.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "demo", "password", "demo123"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getFirst(HttpHeaders.SET_COOKIE);

        org.assertj.core.api.Assertions.assertThat(setCookie)
                .startsWith(AuthController.SESSION_COOKIE + "=");
        String sessionToken = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        client.get().uri("/auth/session")
                .cookie(AuthController.SESSION_COOKIE, sessionToken)
                .headers(headers -> {
                    headers.set("X-Socp-User", "attacker");
                    headers.set("X-Socp-Role", "admin");
                    headers.set("X-Tenant-Id", "victim");
                })
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("demo")
                .jsonPath("$.role").isEqualTo("analyst")
                .jsonPath("$.tenant").isEqualTo("default");
    }
}
