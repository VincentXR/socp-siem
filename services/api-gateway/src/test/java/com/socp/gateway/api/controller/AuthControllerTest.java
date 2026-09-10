package com.socp.gateway.api.controller;

import com.socp.gateway.api.request.LoginRequest;
import com.socp.gateway.api.request.ServiceTokenRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthControllerTest {

    @Test
    void internalServiceGetsBearerTokenOnlyWithSharedCredential() {
        AuthController controller = controller();

        var response = controller.serviceToken(new ServiceTokenRequest("alert-web", "service-secret-0123456789")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(((Map<?, ?>) response.getBody()).get("token"));
    }

    @Test
    void invalidServiceCredentialIsRejected() {
        AuthController controller = controller();

        var response = controller.serviceToken(new ServiceTokenRequest("alert-web", "wrong")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void browserLoginReturnsHttpOnlySessionCookieInsteadOfBearerJson() {
        AuthController controller = controller();
        ReflectionTestUtils.setField(controller, "usersJson", "{\"demo\":\"demo123\"}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{\"demo\":\"analyst\"}");
        controller.init();

        var response = controller.login(new LoginRequest("demo", "demo123")).block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst("Set-Cookie"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertFalse(body.containsKey("token"));
        assertEquals(AuthController.DEFAULT_LOCALE, body.get("locale"));
    }

    @Test
    void carriesConfiguredUserLocaleIntoLoginAndSession() {
        AuthController controller = controller();
        ReflectionTestUtils.setField(controller, "usersJson", "{\"demo\":\"demo123\"}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{\"demo\":\"analyst\"}");
        ReflectionTestUtils.setField(controller, "localesJson", "{\"demo\":\"en-US\"}");
        controller.init();

        var login = controller.login(new LoginRequest("demo", "demo123")).block();
        assertNotNull(login);
        assertEquals("en-US", ((Map<?, ?>) login.getBody()).get("locale"));

        Map<String, Object> session = controller.session("demo", "analyst", "default", "en-US");
        assertEquals("en-US", session.get("locale"));
    }

    @Test
    void operatorsExposeConfiguredNamesAndAlwaysIncludeCurrentIdentity() {
        AuthController controller = controller();
        ReflectionTestUtils.setField(controller, "usersJson", "{\"zeta\":\"secret\",\"alpha\":\"secret\"}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{\"alpha\":\"admin\"}");
        controller.init();

        Map<String, Object> result = controller.operators("oidc-user", "default");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(List.of("alpha", "oidc-user", "zeta"),
                items.stream().map(item -> item.get("id")).toList());
        assertEquals("admin", items.stream().filter(item -> "alpha".equals(item.get("id")))
                .findFirst().orElseThrow().get("role"));
        assertTrue(items.stream().anyMatch(item -> "oidc-user".equals(item.get("id"))
                && Boolean.TRUE.equals(item.get("current"))));
        assertEquals("configured", result.get("source"));

        Map<String, Object> isolated = controller.operators("other-tenant-user", "tenant-b");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> isolatedItems = (List<Map<String, Object>>) isolated.get("items");
        assertEquals(List.of("other-tenant-user"), isolatedItems.stream().map(item -> item.get("id")).toList());
        assertEquals("session", isolated.get("source"));
    }

    private static AuthController controller() {
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "secret",
                "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef");
        ReflectionTestUtils.setField(controller, "serviceSecret", "service-secret-0123456789");
        ReflectionTestUtils.setField(controller, "usersJson", "{}");
        ReflectionTestUtils.setField(controller, "rolesJson", "{}");
        ReflectionTestUtils.setField(controller, "localesJson", "{}");
        controller.init();
        return controller;
    }
}
