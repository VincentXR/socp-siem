package com.socp.gateway.api.controller;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.socp.gateway.security.OidcIdTokenValidator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class OidcBrowserBindingTest {
    @Test void bindsCallbacksToInitiatingBrowserAndPreservesVerifiedAuthorities() throws Exception {
        AtomicInteger exchanges = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/protocol/openid-connect/token", exchange -> {
            exchanges.incrementAndGet();
            byte[] body = "{\"id_token\":\"fixture\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            OidcIdTokenValidator validator = new OidcIdTokenValidator() {
                @Override public JWTClaimsSet validate(String token, String nonce) {
                    return new JWTClaimsSet.Builder().subject("idp-subject").claim("preferred_username", "approver")
                            .claim("role", "analyst").claim("tenant", "tenant-a")
                            .claim("groups", List.of("SOC_APPROVERS"))
                            .claim("permissions", List.of("soar:approve", "unrecognized:grant")).build();
                }
            };
            OidcAuthController controller = new OidcAuthController(new AuthController(), validator);
            ReflectionTestUtils.setField(controller, "issuerUri", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(controller, "clientId", "fixture");
            ReflectionTestUtils.setField(controller, "redirectUri", "https://socp.test/auth/oidc/callback");
            ReflectionTestUtils.setField(controller, "frontendUrl", "https://socp.test/");
            ReflectionTestUtils.setField(controller, "cookieSecure", true);
            var first = login(controller); var second = login(controller);
            assertThat(first.cookie()).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/auth/oidc");
            assertThat(controller.callback("code", first.state(), MockServerHttpRequest.get("/auth/oidc/callback").build())
                    .block().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(callback(controller, first.state(), second.cookie()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchanges).hasValue(0);
            var completed = callback(controller, first.state(), first.cookie());
            assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.FOUND);
            String session = completed.getHeaders().get("Set-Cookie").stream().filter(value -> value.startsWith("SOCP_SESSION=")).findFirst().orElseThrow();
            var claims = SignedJWT.parse(session.substring(session.indexOf('=') + 1, session.indexOf(';'))).getJWTClaimsSet();
            assertThat(claims.getStringListClaim("groups")).contains("SOC_APPROVERS");
            assertThat(claims.getStringListClaim("permissions")).contains("soar:approve", "soar:view").doesNotContain("unrecognized:grant");
            assertThat(completed.getHeaders().get("Set-Cookie")).anyMatch(value -> value.contains("Max-Age=0"));
            assertThat(callback(controller, first.state(), first.cookie()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(callback(controller, second.state(), second.cookie()).getStatusCode()).isEqualTo(HttpStatus.FOUND);
            assertThat(exchanges).hasValue(2);
        } finally { server.stop(0); }
    }
    private record Login(String state, String cookie) { }
    private Login login(OidcAuthController controller) {
        var response = controller.login(MockServerHttpRequest.get("/auth/oidc/login").build()).block();
        String state = Arrays.stream(response.getHeaders().getLocation().getRawQuery().split("&"))
                .filter(value -> value.startsWith("state=")).findFirst().orElseThrow().substring(6);
        return new Login(state, response.getHeaders().getFirst("Set-Cookie"));
    }
    private ResponseEntity<?> callback(OidcAuthController controller, String state, String cookie) {
        String pair = cookie.substring(0, cookie.indexOf(';'));
        return controller.callback("fixture-code", state, MockServerHttpRequest.get("/auth/oidc/callback")
                .cookie(new HttpCookie(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1))).build()).block();
    }
}
