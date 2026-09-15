package com.socp.gateway.filter;

import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.gateway.api.controller.AuthController;
import com.socp.platform.auth.security.JwtValidationException;
import com.socp.platform.auth.security.JwtValidator;
import com.socp.platform.obs.trace.TracePropagation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
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

    private static final TextMapGetter<HttpHeaders> HTTP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpHeaders headers) {
            return headers.keySet();
        }

        @Override
        public String get(HttpHeaders headers, String key) {
            return headers.getFirst(key);
        }
    };

    private static final TextMapSetter<HttpHeaders> HTTP_SETTER = HttpHeaders::set;

    private final JwtValidator jwtValidator;
    private final Set<String> allowedOrigins;
    private final String serviceSecret;

    @Autowired
    public GatewayFilter(JwtValidator jwtValidator,
                         @Value("${socp.auth.allowed-origins:http://localhost:5173,http://localhost:18092}")
                         String allowedOrigins,
                         @Value("${socp.security.service-secret:}") String serviceSecret) {
        this.jwtValidator = jwtValidator;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).collect(Collectors.toUnmodifiableSet());
        this.serviceSecret = serviceSecret == null ? "" : serviceSecret.trim();
    }

    GatewayFilter(JwtValidator jwtValidator) {
        this(jwtValidator, "http://localhost:5173,http://localhost:18092", "");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // The gateway is where an inbound request becomes a trace. Without a
        // span here the first downstream service roots a trace of its own and
        // the north-bound hop is lost, which is why forwarded requests used to
        // carry no traceparent at all and traces began inside the mesh.
        Span span = TracePropagation.startSpan(spanName(exchange), SpanKind.SERVER,
                TracePropagation.extract(HTTP_GETTER, exchange.getRequest().getHeaders()));
        Context spanContext = Context.current().with(span);
        span.setAttribute("http.request.method", methodOf(exchange));
        span.setAttribute("url.path", exchange.getRequest().getPath().value());

        String incomingTrace = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        String spanTraceId = TracePropagation.traceId(spanContext);
        // Effectively final: the response and the forwarded request both capture it.
        final String traceId = spanTraceId != null
                ? spanTraceId
                : (incomingTrace != null && W3C_TRACE_ID.matcher(incomingTrace).matches()
                    ? incomingTrace
                    : UUID.randomUUID().toString().replace("-", ""));
        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set("X-Trace-Id", traceId);
            return Mono.empty();
        });

        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/auth/login") || path.startsWith("/auth/service-token")
                || path.startsWith("/auth/oidc/")) {
            return traced(span, exchange, chain.filter(withTrace(spanContext, exchange, traceId)));
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
            return traced(span, exchange,
                    reject(exchange, traceId, "Missing or invalid session", HttpStatus.UNAUTHORIZED));
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
                return traced(span, exchange,
                        reject(exchange, traceId, "Invalid or expired session", HttpStatus.UNAUTHORIZED));
            }
            if (!TenantContext.isValid(tenant) || subject == null || subject.isBlank()
                    || role == null || !ROLES.contains(role)) {
                return traced(span, exchange, reject(exchange, traceId,
                        "Session identity claims are incomplete", HttpStatus.UNAUTHORIZED));
            }
        }

        if (!TenantContext.isValid(tenant)) {
            return traced(span, exchange,
                    reject(exchange, traceId, "Invalid tenant identity", HttpStatus.UNAUTHORIZED));
        }

        String method = exchange.getRequest().getMethod() == null
                ? "GET" : exchange.getRequest().getMethod().name();
        if ("viewer".equals(role) && !("GET".equals(method) || "OPTIONS".equals(method))) {
            return traced(span, exchange,
                    reject(exchange, traceId, "viewer role is read-only", HttpStatus.FORBIDDEN));
        }
        if (cookieAuthentication && isUnsafe(method)) {
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (origin == null || !allowedOrigins.contains(origin)) {
                return traced(span, exchange, reject(exchange, traceId,
                        "Cross-site session request rejected", HttpStatus.FORBIDDEN));
            }
        }

        String resolvedAuth = auth;
        String resolvedTenant = tenant;
        String resolvedRole = role;
        String resolvedSubject = subject;
        String resolvedLocale = locale;
        ServerWebExchange trusted = exchange.mutate().request(request -> request.headers(headers -> {
            stripServiceIdentity(headers);
            stripGatewayIdentity(headers);
            headers.set("X-Trace-Id", traceId);
            // Overwrite, never append: an inbound header must not let a caller
            // decide the parent of our own span.
            TracePropagation.inject(spanContext, HTTP_SETTER, headers);
            headers.set(HttpHeaders.AUTHORIZATION, resolvedAuth);
            headers.set("X-Tenant-Id", resolvedTenant);
            headers.set("X-Socp-Role", resolvedRole);
            headers.set("X-Socp-User", resolvedSubject);
            headers.set("X-Socp-Locale", resolvedLocale);
            if (!serviceSecret.isBlank()) {
                String gatewayPath = exchange.getRequest().getURI().getRawPath();
                String timestamp = String.valueOf(java.time.Instant.now().getEpochSecond());
                String nonce = UUID.randomUUID().toString();
                String token = resolvedAuth.substring(BEARER.length()).trim();
                headers.set(ServiceRequestSignature.GATEWAY_PATH_HEADER, gatewayPath);
                headers.set(ServiceRequestSignature.GATEWAY_TIMESTAMP_HEADER, timestamp);
                headers.set(ServiceRequestSignature.GATEWAY_NONCE_HEADER, nonce);
                headers.set(ServiceRequestSignature.GATEWAY_SIGNATURE_HEADER,
                        ServiceRequestSignature.sign(serviceSecret,
                                ServiceRequestSignature.GATEWAY_SERVICE, method,
                                ServiceRequestSignature.gatewayBinding(gatewayPath, token),
                                resolvedTenant, timestamp, nonce));
            }
        })).build();
        return traced(span, exchange, chain.filter(trusted));
    }

    private static ServerWebExchange withTrace(Context spanContext, ServerWebExchange exchange,
                                               String traceId) {
        return exchange.mutate().request(request -> request.headers(headers -> {
            stripServiceIdentity(headers);
            stripGatewayIdentity(headers);
            headers.set("X-Trace-Id", traceId);
            TracePropagation.inject(spanContext, HTTP_SETTER, headers);
            headers.remove("X-Socp-Role");
            headers.remove("X-Socp-User");
            headers.remove("X-Socp-Locale");
            headers.remove("X-Tenant-Id");
        })).build();
    }

    private static String methodOf(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == null
                ? "HTTP" : exchange.getRequest().getMethod().name();
    }

    private static String spanName(ServerWebExchange exchange) {
        return methodOf(exchange) + " " + exchange.getRequest().getPath().value();
    }

    /**
     * Ends the gateway span once the exchange settles. WebFlux gives no
     * request-scoped thread, so the span lifecycle has to be bound to the
     * reactive signal rather than to a try/finally block.
     */
    private static Mono<Void> traced(Span span, ServerWebExchange exchange, Mono<Void> result) {
        return result
                .doOnError(throwable -> {
                    span.recordException(throwable);
                    span.setStatus(StatusCode.ERROR);
                })
                .doFinally(signal -> {
                    var status = exchange.getResponse().getStatusCode();
                    if (status != null) {
                        span.setAttribute("http.response.status_code", status.value());
                        if (status.is5xxServerError()) {
                            span.setStatus(StatusCode.ERROR);
                        }
                    }
                    span.end();
                });
    }

    private static void stripServiceIdentity(org.springframework.http.HttpHeaders headers) {
        headers.remove(ServiceRequestSignature.SERVICE_HEADER);
        headers.remove(ServiceRequestSignature.TIMESTAMP_HEADER);
        headers.remove(ServiceRequestSignature.NONCE_HEADER);
        headers.remove(ServiceRequestSignature.SIGNATURE_HEADER);
    }

    private static void stripGatewayIdentity(org.springframework.http.HttpHeaders headers) {
        headers.remove(ServiceRequestSignature.GATEWAY_PATH_HEADER);
        headers.remove(ServiceRequestSignature.GATEWAY_TIMESTAMP_HEADER);
        headers.remove(ServiceRequestSignature.GATEWAY_NONCE_HEADER);
        headers.remove(ServiceRequestSignature.GATEWAY_SIGNATURE_HEADER);
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
