package com.socp.gateway.api.controller;

import com.socp.gateway.security.OidcIdTokenValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OidcAuthControllerTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesTheBoundedHandlerForTokenEndpointResponses() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/protocol/openid-connect/token", this::respondRejected);
        server.start();

        OidcAuthController controller = new OidcAuthController(
                mock(AuthController.class), mock(OidcIdTokenValidator.class));
        ReflectionTestUtils.setField(controller, "issuerUri", "http://localhost:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(controller, "redirectUri", "http://localhost:5173/auth/oidc/callback");
        ReflectionTestUtils.setField(controller, "clientId", "socp-spa");
        ReflectionTestUtils.setField(controller, "responseBodyLimitBytes", 1024);

        Method exchangeCode = OidcAuthController.class.getDeclaredMethod(
                "exchangeCode", String.class, String.class, String.class);
        exchangeCode.setAccessible(true);

        assertThatThrownBy(() -> {
            try {
                exchangeCode.invoke(controller, "code", "verifier", "nonce");
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned 401");
    }

    @Test
    void mapsOnlyRolesTheGatewayCanAdmit() {
        // The IdP role mapping must stay a permutation of the issuable vocabulary:
        // mapping an advisory name from docs/soar-design.md (approver) would issue
        // a session that GatewayFilter then rejects with 401.
        org.assertj.core.api.Assertions
                .assertThat(OidcAuthController.ROLE_PRECEDENCE)
                .containsExactlyInAnyOrderElementsOf(
                        com.socp.platform.auth.security.Permission.ISSUABLE_ROLES);
    }

    private void respondRejected(HttpExchange exchange) throws IOException {
        byte[] body = "rejected".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(401, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
