package com.socp.gateway.api.controller;

import com.socp.gateway.api.request.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.gateway.oidc.InMemoryOidcStateStore;
import com.socp.gateway.oidc.OidcStateStore;
import com.socp.gateway.security.OidcIdTokenValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keycloak OIDC authorization-code flow with PKCE, nonce and ID-token verification. */
@RestController
@RequestMapping("/auth/oidc")
public class OidcAuthController {

    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    private static final Logger log = LoggerFactory.getLogger(OidcAuthController.class);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final AuthController authController;
    private final OidcIdTokenValidator idTokenValidator;
    private final OidcStateStore stateStore;

    @Value("${socp.oidc.issuer-uri:}") private String issuerUri;
    @Value("${socp.oidc.client-id:socp-spa}") private String clientId;
    @Value("${socp.oidc.redirect-uri:}") private String redirectUri;
    @Value("${socp.oidc.frontend-url:http://localhost:5173}") private String frontendUrl;

    public OidcAuthController(AuthController authController,
                              OidcIdTokenValidator idTokenValidator) {
        this(authController, idTokenValidator, new InMemoryOidcStateStore());
    }

    @Autowired
    public OidcAuthController(AuthController authController,
                              OidcIdTokenValidator idTokenValidator,
                              OidcStateStore stateStore) {
        this.authController = authController;
        this.idTokenValidator = idTokenValidator;
        this.stateStore = stateStore;
    }

    @GetMapping("/login")
    public Mono<ResponseEntity<?>> login() {
        if (issuerUri == null || issuerUri.isBlank()) {
            return Mono.just(error(HttpStatus.INTERNAL_SERVER_ERROR, "OIDC issuer is not configured"));
        }
        String verifier = randomUrlToken();
        String state = randomUrlToken();
        String nonce = randomUrlToken();
        long now = System.currentTimeMillis();
        String authUrl = issuer() + "/protocol/openid-connect/auth"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&scope=openid%20profile"
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(codeChallenge(verifier))
                + "&code_challenge_method=S256";
        ResponseEntity<?> redirect = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authUrl)).build();
        return stateStore.save(state, new OidcStateStore.Entry(verifier, nonce, now + STATE_TTL.toMillis()), STATE_TTL)
                .thenReturn(redirect);
    }

    @GetMapping("/callback")
    public Mono<ResponseEntity<?>> callback(@RequestParam("code") String code,
                                             @RequestParam("state") String state) {
        return stateStore.consume(state)
                .filter(entry -> entry.expiresAt() > System.currentTimeMillis())
                .flatMap(entry -> Mono.<ResponseEntity<?>>fromCallable(() -> completeCallback(code, entry))
                        .subscribeOn(Schedulers.boundedElastic()))
                .switchIfEmpty(Mono.just(error(HttpStatus.UNAUTHORIZED, "OIDC state is invalid or expired")))
                .onErrorResume(failure -> {
                    log.warn("OIDC callback rejected: {}", failure.getMessage());
                    return Mono.just(error(HttpStatus.UNAUTHORIZED, "OIDC login failed"));
                });
    }

    private ResponseEntity<?> completeCallback(String code, OidcStateStore.Entry entry) {
        try {
            Map<String, Object> claims = exchangeCode(code, entry.verifier(), entry.nonce());
            String subject = first(claims, "preferred_username", "sub");
            String role = role(claims);
            String tenant = first(claims, "tenant", "tenant_id");
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("OIDC subject is missing");
            if (role == null) throw new IllegalArgumentException("OIDC user has no supported SOCP role");
            if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("OIDC tenant claim is missing");

            String token = authController.sign(subject, role, tenant);
            log.info("OIDC login succeeded subject={} role={} tenant={}", subject, role, tenant);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, authController.sessionCookie(token).toString())
                    .location(URI.create(frontendUrl))
                    .build();
        } catch (Exception failure) {
            log.warn("OIDC callback rejected: {}", failure.getMessage());
            return error(HttpStatus.UNAUTHORIZED, "OIDC login failed");
        }
    }

    private Map<String, Object> exchangeCode(String code, String verifier, String nonce) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId)
                + "&code_verifier=" + enc(verifier);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(issuer() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OIDC token endpoint returned " + response.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.body(), Map.class);
        JWTClaimsSet claims = idTokenValidator.validate(String.valueOf(body.get("id_token")), nonce);
        return new LinkedHashMap<>(claims.getClaims());
    }

    private String role(Map<String, Object> claims) {
        Set<String> roles = new LinkedHashSet<>();
        Object direct = claims.get("role");
        if (direct != null) roles.add(String.valueOf(direct).toLowerCase());
        collectRoles(roles, claims.get("realm_access"));
        Object clients = claims.get("resource_access");
        if (clients instanceof Map<?, ?> access) collectRoles(roles, access.get(clientId));
        for (String supported : List.of("admin", "analyst", "viewer")) {
            if (roles.contains(supported)) return supported;
        }
        return null;
    }

    private static void collectRoles(Set<String> out, Object container) {
        if (!(container instanceof Map<?, ?> map)) return;
        Object values = map.get("roles");
        if (!(values instanceof Iterable<?> iterable)) return;
        for (Object value : iterable) out.add(String.valueOf(value).toLowerCase());
    }

    private static String first(Map<String, Object> claims, String primary, String fallback) {
        Object value = claims.get(primary);
        if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        value = claims.get(fallback);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private String issuer() {
        String value = issuerUri == null ? "" : issuerUri.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String randomUrlToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to create PKCE challenge", failure);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", status.value(), "message", message));
    }
}
