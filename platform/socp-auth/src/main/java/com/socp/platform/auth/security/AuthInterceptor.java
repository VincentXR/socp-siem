package com.socp.platform.auth.security;
import com.nimbusds.jwt.JWTClaimsSet;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.socp.platform.tenant.security.ServiceRequestSignature;
import com.socp.platform.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/** Authenticates user JWTs, fixed ingest credentials, and signed internal service requests. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final JwtValidator jwtValidator;
    private final SocpSecurityProperties properties;
    private final CollectorCredentialRegistry collectorCredentials;
    private final ConcurrentHashMap<String, Long> serviceNonces = new ConcurrentHashMap<>();
    private volatile long nextNonceCleanupAt;

    public AuthInterceptor(JwtValidator jwtValidator, SocpSecurityProperties properties) {
        this(jwtValidator, properties, new CollectorCredentialRegistry(properties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthInterceptor(JwtValidator jwtValidator, SocpSecurityProperties properties,
                           CollectorCredentialRegistry collectorCredentials) {
        this.jwtValidator = jwtValidator;
        this.properties = properties;
        this.collectorCredentials = collectorCredentials;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw ApiException.unauthorized("Missing Bearer token");
        }
        String token = authorization.substring(BEARER.length()).trim();
        if (token.isEmpty()) throw ApiException.unauthorized("Empty Bearer token");

        String metricsToken = properties.getMetricsToken();
        if (metricsToken != null && !metricsToken.isBlank() && constantTimeEquals(metricsToken, token)) {
            if (!isAllowedMetricsRequest(request)) {
                throw ApiException.forbidden("Metrics credential is limited to actuator metrics");
            }
            TenantContext.set("default");
            return true;
        }

        String tenant = null;
        String role;
        boolean ingestCredential = false;
        CollectorCredentialRegistry.Identity collectorIdentity =
                collectorCredentials.authenticate(token).orElse(null);
        String ingestToken = properties.getIngestToken();
        if (collectorIdentity != null) {
            if (!isAllowedIngestRequest(request)) {
                throw ApiException.forbidden("Collector credential is limited to the ingest endpoint");
            }
            String claimedCollector = request.getHeader("X-SOCP-Collector");
            if (claimedCollector != null && !claimedCollector.isBlank()
                    && !collectorIdentity.collectorId().equals(claimedCollector.trim())) {
                throw ApiException.unauthorized("Collector identity header does not match the credential");
            }
            tenant = collectorIdentity.tenantId();
            role = "ingest";
            ingestCredential = true;
            request.setAttribute(CollectorCredentialRegistry.COLLECTOR_ID_ATTRIBUTE,
                    collectorIdentity.collectorId());
        } else if (ingestToken != null && !ingestToken.isBlank() && constantTimeEquals(ingestToken, token)) {
            if (!properties.isAllowGlobalIngestToken()) {
                throw ApiException.unauthorized("Global ingest credentials are disabled");
            }
            if (!isAllowedIngestRequest(request)) {
                throw ApiException.forbidden("Static ingest credential is limited to the ingest endpoint");
            }
            tenant = "default";
            role = "ingest";
            ingestCredential = true;
            // The legacy token has no per-source identity. Do not trust a
            // caller-controlled collector header; production uses the
            // registry branch above for tenant/source binding.
        } else if (jwtValidator.isDevBypass()) {
            role = request.getHeader("X-Role");
        } else {
            JWTClaimsSet claims;
            try {
                claims = jwtValidator.validate(token);
            } catch (JwtValidationException invalid) {
                log.warn("JWT rejected path={} reason={}", request.getRequestURI(), invalid.getMessage());
                throw ApiException.unauthorized(invalid.getMessage());
            }
            tenant = jwtValidator.extractTenant(claims);
            try {
                role = claims.getStringClaim("role");
            } catch (Exception missingRole) {
                role = null;
            }
        }

        String delegatedTenant = verifyServiceIdentity(request);
        if (delegatedTenant != null) {
            tenant = delegatedTenant;
            role = "analyst";
            request.setAttribute(RequireService.SERVICE_ID_ATTRIBUTE,
                    request.getHeader(ServiceRequestSignature.SERVICE_HEADER));
        } else if (ingestCredential) {
            String requestedTenant = request.getHeader("X-Tenant-Id");
            if (requestedTenant != null && !requestedTenant.isBlank()) {
                String boundTenant = collectorIdentity == null ? "default" : collectorIdentity.tenantId();
                if (!boundTenant.equals(requestedTenant)) {
                    throw ApiException.unauthorized("Ingest credential is bound to a different tenant");
                }
            }
        }

        requireIdentity(handler, delegatedTenant != null, ingestCredential);
        requireRole(handler, role, request.getRequestURI());

        if (tenant != null && !tenant.isBlank()) {
            if (!TenantContext.isValid(tenant)) throw ApiException.unauthorized("Invalid tenant claim");
            TenantContext.set(tenant);
        } else if (jwtValidator.isDevBypass()) {
            String headerTenant = request.getHeader("X-Tenant-Id");
            if (headerTenant == null || headerTenant.isBlank()) headerTenant = "default";
            if (!TenantContext.isValid(headerTenant)) throw ApiException.unauthorized("Invalid tenant header");
            TenantContext.set(headerTenant);
        } else {
            throw ApiException.unauthorized("Authenticated identity has no tenant claim");
        }
        return true;
    }

    private void requireRole(Object handler, String role, String path) {
        if (!(handler instanceof HandlerMethod method)) return;
        RequireRole requirement = method.getMethodAnnotation(RequireRole.class);
        if (requirement == null) requirement = method.getBeanType().getAnnotation(RequireRole.class);
        if (requirement == null || requirement.value().length == 0) return;
        String effectiveRole = role == null || role.isBlank() ? "anonymous" : role;
        for (String accepted : requirement.value()) {
            if (accepted.equalsIgnoreCase(effectiveRole)) return;
        }
        log.warn("RBAC rejected path={} role={} required={}",
                path, effectiveRole, String.join(",", requirement.value()));
        throw ApiException.forbidden(requirement.message());
    }

    private void requireIdentity(Object handler, boolean serviceIdentity, boolean collectorIdentity) {
        if (!(handler instanceof HandlerMethod method)) return;
        RequireService service = method.getMethodAnnotation(RequireService.class);
        if (service == null) service = method.getBeanType().getAnnotation(RequireService.class);
        if (service != null && !serviceIdentity) {
            throw ApiException.forbidden(service.message());
        }
        RequireIngestIdentity ingest = method.getMethodAnnotation(RequireIngestIdentity.class);
        if (ingest == null) ingest = method.getBeanType().getAnnotation(RequireIngestIdentity.class);
        if (ingest != null && !serviceIdentity && !collectorIdentity) {
            throw ApiException.forbidden(ingest.message());
        }
    }

    private boolean isAllowedIngestRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        String relativePath = contextPath == null || contextPath.isBlank() || !path.startsWith(contextPath)
                ? path : path.substring(contextPath.length());
        return properties.getIngestPaths().stream()
                .anyMatch(configured -> configured.equals(path) || configured.equals(relativePath));
    }

    private static boolean isAllowedMetricsRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        return path != null && (path.matches(".*/actuator/prometheus/?")
                || path.matches(".*/actuator/metrics(?:/[^/]+)?/?"));
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private String verifyServiceIdentity(HttpServletRequest request) {
        String service = request.getHeader(ServiceRequestSignature.SERVICE_HEADER);
        String timestamp = request.getHeader(ServiceRequestSignature.TIMESTAMP_HEADER);
        String nonce = request.getHeader(ServiceRequestSignature.NONCE_HEADER);
        String signature = request.getHeader(ServiceRequestSignature.SIGNATURE_HEADER);
        boolean anyHeader = service != null || timestamp != null || nonce != null || signature != null;
        if (!anyHeader) return null;
        if (isBlank(service) || isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            throw ApiException.unauthorized("Incomplete service identity proof");
        }
        if (!service.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || nonce.length() > 128) {
            throw ApiException.unauthorized("Invalid service identity proof");
        }
        String secret = properties.getServiceSecret();
        if (secret == null || secret.isBlank()) {
            throw ApiException.unauthorized("Service identity verification is not configured");
        }
        long signedAt;
        try {
            signedAt = Long.parseLong(timestamp);
        } catch (NumberFormatException invalidTimestamp) {
            throw ApiException.unauthorized("Invalid service identity timestamp");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - signedAt) > properties.getServiceMaxSkewSeconds()) {
            throw ApiException.unauthorized("Expired service identity proof");
        }
        String tenant = request.getHeader("X-Tenant-Id");
        if (!TenantContext.isValid(tenant)) throw ApiException.unauthorized("Invalid delegated tenant");
        if (!ServiceRequestSignature.verify(secret, signature, service, request.getMethod(),
                request.getRequestURI(), tenant, timestamp, nonce)) {
            throw ApiException.unauthorized("Invalid service identity signature");
        }
        cleanupNonces(now);
        if (serviceNonces.putIfAbsent(service + ':' + nonce, now) != null) {
            throw ApiException.unauthorized("Replayed service identity proof");
        }
        return tenant;
    }

    private void cleanupNonces(long now) {
        if (now < nextNonceCleanupAt) return;
        long cutoff = now - properties.getServiceMaxSkewSeconds();
        serviceNonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        nextNonceCleanupAt = now + properties.getServiceMaxSkewSeconds();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
