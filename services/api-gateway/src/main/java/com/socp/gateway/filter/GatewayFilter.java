package com.socp.gateway.filter;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.gateway.api.controller.AuthController;
import com.socp.platform.auth.security.JwtValidationException;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.regex.Pattern;

/** Authenticates north-bound traffic and forwards only trusted identity headers. */
@Component
public class GatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayFilter.class);
    private static final String BEARER = "Bearer ";
    private static final Set<String> ROLES = Set.of("admin", "analyst", "viewer");
    private static final Pattern W3C_TRACE_ID = Pattern.compile("(?!0{32})[0-9a-f]{32}");
    private final JwtValidator jwtValidator;
    private final Set<String> allowedOrigins;

    @Autowired
    public GatewayFilter(JwtValidator jwtValidator,
                         @Value("${socp.auth.allowed-origins:http://localhost:5173,http://localhost:18092}")
                         String allowedOrigins) {
        this.jwtValidator = jwtValidator;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    GatewayFilter(JwtValidator jwtValidator) {
        this(jwtValidator, "http://localhost:5173,http://localhost:18092");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incomingTrace = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        String traceId = incomingTrace != null && W3C_TRACE_ID.matcher(incomingTrace).matches()
                ? incomingTrace
                : UUID.randomUUID().toString().replace("-", "");
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            return Mono.empty();
        });

        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/auth/login") || path.startsWith("/auth/service-token")
                || path.startsWith("/auth/oidc/")) {
            return chain.filter(withTrace(exchange, traceId));
        }

        String auth = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean cookieAuthentication = false;
        if (!validBearer(auth)) {
            var cookie = exchange.getRequest().getCookies().getFirst(AuthController.SESSION_COOKIE);
            if (cookie != null && !cookie.getValue().isBlank()) {
                auth = BEARER + cookie.getValue();
                cookieAuthentication = true;
            }
        }
        if (!validBearer(auth)) {
            log.warn("Authentication rejected traceId={} path={} reason=missing-credentials", traceId, path);
            return reject(exchange, traceId, "Missing or invalid session", HttpStatus.UNAUTHORIZED);
        }

        String tenant;
        String role;
        String subject;
        String locale;
        if (jwtValidator.isDevBypass()) {
            tenant = defaultValue(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id"), "default");
            role = "analyst";
            subject = "dev-user";
            locale = defaultLocale(exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE));
        } else {
            JWTClaimsSet claims;
            try {
                claims = jwtValidator.validate(auth.substring(BEARER.length()));
                tenant = jwtValidator.extractTenant(claims);
                role = claims.getStringClaim("role");
                subject = claims.getSubject();
                String tokenLocale = AuthController.normalizeLocale(claims.getStringClaim("locale"));
                locale = tokenLocale == null
                        ? defaultLocale(exchange.getRequest().getHeaders().getFirst(HttpHeaders.ACCEPT_LANGUAGE))
                        : tokenLocale;
            } catch (JwtValidationException | java.text.ParseException failure) {
                log.warn("Authentication rejected traceId={} path={} reason={}",
                        traceId, path, failure.getMessage());
                return reject(exchange, traceId, "Invalid or expired session", HttpStatus.UNAUTHORIZED);
            }
            if (!TenantContext.isValid(tenant) || subject == null || subject.isBlank()
                    || role == null || !ROLES.contains(role)) {
                return reject(exchange, traceId, "Session identity claims are incomplete",
                        HttpStatus.UNAUTHORIZED);
            }
        }

        if (!TenantContext.isValid(tenant)) {
            return reject(exchange, traceId, "Invalid tenant identity", HttpStatus.UNAUTHORIZED);
        }

        String method = exchange.getRequest().getMethod() == null
                ? "GET" : exchange.getRequest().getMethod().name();
        if ("viewer".equals(role) && !("GET".equals(method) || "OPTIONS".equals(method))) {
            return reject(exchange, traceId, "viewer role is read-only", HttpStatus.FORBIDDEN);
        }
        if (cookieAuthentication && isUnsafe(method)) {
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (origin == null || !allowedOrigins.contains(origin)) {
                return reject(exchange, traceId, "Cross-site session request rejected",
                        HttpStatus.FORBIDDEN);
            }
        }

        String resolvedAuth = auth;
        String resolvedTenant = tenant;
        String resolvedRole = role;
        String resolvedSubject = subject;
        String resolvedLocale = locale;
        ServerWebExchange trusted = exchange.mutate().request(request -> request.headers(headers -> {
            stripServiceIdentity(headers);
            headers.set("X-Trace-Id", traceId);
            headers.set(HttpHeaders.AUTHORIZATION, resolvedAuth);
            headers.set("X-Tenant-Id", resolvedTenant);
            headers.set("X-Socp-Role", resolvedRole);
            headers.set("X-Socp-User", resolvedSubject);
            headers.set("X-Socp-Locale", resolvedLocale);
        })).build();
        return chain.filter(trusted);
    }

    private static ServerWebExchange withTrace(ServerWebExchange exchange, String traceId) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            stripServiceIdentity(headers);
            headers.set("X-Trace-Id", traceId);
            headers.remove("X-Socp-Role");
            headers.remove("X-Socp-User");
            headers.remove("X-Socp-Locale");
            headers.remove("X-Tenant-Id");
        })).build();
    }

    private static void stripServiceIdentity(org.springframework.http.HttpHeaders headers) {
        headers.remove(ServiceRequestSignature.SERVICE_HEADER);
        headers.remove(ServiceRequestSignature.TIMESTAMP_HEADER);
        headers.remove(ServiceRequestSignature.NONCE_HEADER);
        headers.remove(ServiceRequestSignature.SIGNATURE_HEADER);
    }

    private static boolean isUnsafe(String method) {
        return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    private static boolean validBearer(String auth) {
        return auth != null && auth.startsWith(BEARER)
                && !auth.substring(BEARER.length()).isBlank();
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String defaultLocale(String value) {
        String normalized = AuthController.normalizeLocale(value);
        return normalized == null ? AuthController.DEFAULT_LOCALE : normalized;
    }

    private Mono<Void> reject(ServerWebExchange exchange, String traceId,
                              String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + escapeJson(message)
                + "\",\"traceId\":\"" + traceId + "\",\"timestamp\":\"" + Instant.now() + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
