package com.socp.gateway.api.controller;

import com.socp.gateway.api.request.LoginRequest;
import com.socp.gateway.api.request.ServiceTokenRequest;
import com.socp.gateway.security.AuthAttemptLimiter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Issues short-lived SOCP sessions and keeps bearer tokens out of browser JavaScript. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    public static final String SESSION_COOKIE = "SOCP_SESSION";
    public static final String DEFAULT_LOCALE = "zh-CN";
    private static final long EXPIRES_SECONDS = 1800;

    @Value("${socp.auth.users:}") private String usersJson;
    @Value("${socp.auth.roles:}") private String rolesJson;
    @Value("${socp.auth.login-secret}") private String secret;
    @Value("${socp.auth.locales:}") private String localesJson;
    @Value("${socp.auth.cookie-secure:false}") private boolean cookieSecure;
    @Value("${socp.security.service-secret:}") private String serviceSecret;
    @Value("${socp.security.audience:socp-api}") private String audience;

    private Map<String, String> users = Map.of();
    private Map<String, String> roles = Map.of();
    private Map<String, String> locales = Map.of();
    private final AuthAttemptLimiter attemptLimiter;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(AuthAttemptLimiter attemptLimiter, ObjectMapper objectMapper) {
        this.attemptLimiter = attemptLimiter;
        this.objectMapper = objectMapper;
    }

    /** Focused unit-test constructor; Spring always uses the limiter constructor above. */
    AuthController() {
        this(new AuthAttemptLimiter() {
            @Override
            public Mono<Decision> acquire(String kind, String clientAddress, String identity) {
                return Mono.just(Decision.permit());
            }

            @Override
            public Mono<Void> reset(String kind, String clientAddress, String identity) {
                return Mono.empty();
            }
        }, new ObjectMapper());
    }

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            users = usersJson == null || usersJson.isBlank() ? Map.of() : parseJson(usersJson);
            roles = rolesJson == null || rolesJson.isBlank() ? Map.of() : parseJson(rolesJson);
            locales = localesJson == null || localesJson.isBlank() ? Map.of() : parseJson(localesJson);
        } catch (Exception failure) {
            throw new IllegalStateException("socp.auth users/roles/locales configuration is invalid", failure);
        }
    }

    private Map<String, String> parseJson(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> login(@Valid @RequestBody LoginRequest body, ServerHttpRequest request) {
        String username = body == null ? null : body.username();
        String password = body == null ? null : body.password();
        String address = clientAddress(request);
        return attemptLimiter.acquire("login", address, username).flatMap(decision -> {
            if (!decision.allowed()) return Mono.just(rateLimited(decision.retryAfterSeconds()));
            if (username == null || password == null || !password.equals(users.get(username))) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("code", 401, "message", "Invalid username or password")));
            }
            String role = supportedRole(roles.getOrDefault(username, "analyst"));
            String locale = resolveLocale(username, request == null
                    ? null : request.getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE));
            String token = sign(username, role, "default", locale);
            Map<String, Object> response = Map.of(
                    "username", username,
                    "role", role,
                    "tenant", "default",
                    "locale", locale,
                    "expiresIn", EXPIRES_SECONDS);
            return attemptLimiter.reset("login", address, username).thenReturn(ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
                    .body(response));
        });
    }

    Mono<ResponseEntity<?>> login(LoginRequest body) {
        return login(body, null);
    }

    /** Token endpoint for internal services; unlike browser login it returns the bearer token. */
    @PostMapping(value = "/service-token", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> serviceToken(@Valid @RequestBody ServiceTokenRequest body,
                                                 ServerHttpRequest request) {
        String service = body == null ? null : body.service();
        String suppliedSecret = body == null ? null : body.secret();
        String address = clientAddress(request);
        return attemptLimiter.acquire("service", address, service).flatMap(decision -> {
            if (!decision.allowed()) return Mono.just(rateLimited(decision.retryAfterSeconds()));
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
            return attemptLimiter.reset("service", address, service).thenReturn(ResponseEntity.ok(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", EXPIRES_SECONDS)));
        });
    }

    Mono<ResponseEntity<?>> serviceToken(ServiceTokenRequest body) {
        return serviceToken(body, null);
    }

    @GetMapping("/session")
    public Map<String, Object> session(
            @RequestHeader("X-Socp-User") String username,
            @RequestHeader("X-Socp-Role") String role,
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-Socp-Locale") String locale) {
        return Map.of("username", username, "role", supportedRole(role), "tenant", tenant,
                "locale", resolveLocale(username, locale));
    }

    /** Compatibility overload for direct callers that do not have trusted identity headers. */
    Map<String, Object> session(String username, String role, String tenant) {
        return session(username, role, tenant, DEFAULT_LOCALE);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        ResponseCookie expired = ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).secure(cookieSecure).sameSite("Lax").path("/")
                .maxAge(Duration.ZERO).build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    public String sign(String username, String role, String tenant) {
        return sign(username, role, tenant, DEFAULT_LOCALE);
    }

    public String sign(String username, String role, String tenant, String locale) {
        try {
            Instant now = Instant.now();
            String resolvedLocale = normalizeLocale(locale);
            if (resolvedLocale == null) resolvedLocale = DEFAULT_LOCALE;
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .jwtID(UUID.randomUUID().toString())
                    .issuer("socp-gateway")
                    .audience(audiences())
                    .claim("tenant", tenant == null || tenant.isBlank() ? "default" : tenant)
                    .claim("role", supportedRole(role))
                    .claim("permissions", com.socp.platform.auth.security.Permission
                            .roleDefaults(supportedRole(role)))
                    .claim("locale", resolvedLocale)
                    .claim("identity_type", username != null && username.startsWith("service:")
                            ? "service" : "user")
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

    /** Resolve a trusted configured profile locale, then a client language hint, then the default. */
    String resolveLocale(String username, String requestedLocale) {
        String configured = locales.get(username);
        String resolved = normalizeLocale(configured);
        if (resolved != null) return resolved;
        resolved = normalizeLocale(requestedLocale);
        return resolved == null ? DEFAULT_LOCALE : resolved;
    }

    /** Normalize a BCP-47 language tag or the first value in Accept-Language. */
    public static String normalizeLocale(String value) {
        if (value == null || value.isBlank()) return null;
        String candidate = value.trim().split("[,;]", 2)[0].trim().replace('_', '-');
        return switch (candidate.toLowerCase(Locale.ROOT)) {
            case "zh", "zh-cn" -> "zh-CN";
            case "en", "en-us" -> "en-US";
            default -> null;
        };
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

    private static ResponseEntity<?> rateLimited(long retryAfterSeconds) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)))
                .body(Map.of("code", 429, "message", "Too many authentication attempts"));
    }

    private static String clientAddress(ServerHttpRequest request) {
        if (request == null || request.getRemoteAddress() == null
                || request.getRemoteAddress().getAddress() == null) return "unknown";
        return request.getRemoteAddress().getAddress().getHostAddress();
    }
}
