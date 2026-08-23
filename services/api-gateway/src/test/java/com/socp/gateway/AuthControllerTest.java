package com.socp.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthControllerTest {

    @Test
    void internalServiceGetsBearerTokenOnlyWithSharedCredential() {
        AuthController controller = controller();

        var response = controller.serviceToken(Map.of(
                "service", "alert-web", "secret", "service-secret-0123456789")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(((Map<?, ?>) response.getBody()).get("token"));
    }

    @Test
    void invalidServiceCredentialIsRejected() {
        AuthController controller = controller();

        var response = controller.serviceToken(Map.of(
                "service", "alert-web", "secret", "wrong")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void browserLoginReturnsHttpOnlySessionCookieInsteadOfBearerJson() {
        AuthController controller = controller();
        ReflectionTestUtils.setField(controller, "usersJson", "{\"demo\":\"demo123\"}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{\"demo\":\"analyst\"}");
        controller.init();

        var response = controller.login(Map.of("username", "demo", "password", "demo123")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst("Set-Cookie"));
        assertFalse(((Map<?, ?>) response.getBody()).containsKey("token"));
    }

    private static AuthController controller() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "secret",
                "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(controller, "serviceSecret", "service-secret-0123456789");
        ReflectionTestUtils.setField(controller, "usersJson", "{}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{}");
        controller.init();
        return controller;
    }
}
