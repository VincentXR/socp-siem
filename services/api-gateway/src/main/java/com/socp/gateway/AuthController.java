package com.socp.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Issues short-lived SOCP sessions and keeps bearer tokens out of browser JavaScript. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    public static final String SESSION_COOKIE = "SOCP_SESSION";
    private static final long EXPIRES_SECONDS = 1800;

    @Value("${socp.auth.users:}") private String usersJson;
    @Value("${socp.auth.roles:}") private String rolesJson;
    @Value("${socp.auth.login-secret}") private String secret;
    @Value("${socp.auth.cookie-secure:false}") private boolean cookieSecure;
    @Value("${socp.security.service-secret:}") private String serviceSecret;
    @Value("${socp.security.audience:socp-api}") private String audience;

    private Map<String, String> users = Map.of();
    private Map<String, String> roles = Map.of();

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            users = usersJson == null || usersJson.isBlank() ? Map.of() : parseJson(usersJson);
            roles = rolesJson == null || rolesJson.isBlank() ? Map.of() : parseJson(rolesJson);
        } catch (Exception failure) {
            throw new IllegalStateException("socp.auth users/roles configuration is invalid", failure);
        }
    }

    private static Map<String, String> parseJson(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> login(@RequestBody Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (username == null || password == null || !password.equals(users.get(username))) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "Invalid username or password")));
        }
        String role = supportedRole(roles.getOrDefault(username, "analyst"));
        String token = sign(username, role, "default");
        Map<String, Object> response = Map.of(
                "username", username,
                "role", role,
                "tenant", "default",
                "expiresIn", EXPIRES_SECONDS);
        return Mono.just(ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
                .body(response));
    }

    /** Token endpoint for internal services; unlike browser login it returns the bearer token. */
    @PostMapping(value = "/service-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> serviceToken(@RequestBody Map<String, String> body) {
        String service = body == null ? null : body.get("service");
        String suppliedSecret = body == null ? null : body.get("secret");
        if (service == null || !service.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                || serviceSecret == null || serviceSecret.isBlank()
                || suppliedSecret == null
                || !java.security.MessageDigest.isEqual(
                        serviceSecret.getBytes(StandardCharsets.UTF_8),
                        suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", 401, "message", "Invalid service credentials")));
        }
        String token = sign("service:" + service, "analyst", "default");
        return Mono.just(ResponseEntity.ok(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "expiresIn", EXPIRES_SECONDS)));
    }

    @GetMapping("/session")
    public Map<String, Object> session(
            @RequestHeader(value = "X-Socp-User", defaultValue = "socp-user") String username,
            @RequestHeader(value = "X-Socp-Role", defaultValue = "analyst") String role,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenant) {
        return Map.of("username", username, "role", supportedRole(role), "tenant", tenant);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        ResponseCookie expired = ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    public String sign(String username, String role, String tenant) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .issuer("socp-gateway")
                    .audience(audiences())
                    .claim("tenant", tenant == null || tenant.isBlank() ? "default" : tenant)
                    .claim("role", supportedRole(role))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(EXPIRES_SECONDS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to issue SOCP session", failure);
        }
    }

    private List<String> audiences() {
        return java.util.Arrays.stream(audience == null ? new String[0] : audience.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    public ResponseCookie sessionCookie(String token) {
        return ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(EXPIRES_SECONDS))
                .build();
    }

    private static String supportedRole(String role) {
        String normalized = role == null ? "" : role.trim().toLowerCase();
        if (!java.util.Set.of("admin", "analyst", "viewer").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported SOCP role");
        }
        return normalized;
    }
}
