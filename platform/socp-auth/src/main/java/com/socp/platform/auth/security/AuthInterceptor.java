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
import java.util.concurrent.ConcurrentHashMap;

/** Authenticates user JWTs, fixed ingest credentials, and signed internal service requests. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final JwtValidator jwtValidator;
    private final SocpSecurityProperties properties;
    private final ConcurrentHashMap<String, Long> serviceNonces = new ConcurrentHashMap<>();
    private volatile long nextNonceCleanupAt;

    public AuthInterceptor(JwtValidator jwtValidator, SocpSecurityProperties properties) {
        this.jwtValidator = jwtValidator;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw ApiException.unauthorized("Missing Bearer token");
        }
        String token = authorization.substring(BEARER.length()).trim();
        if (token.isEmpty()) throw ApiException.unauthorized("Empty Bearer token");

        String tenant = null;
        String role;
        boolean ingestCredential = false;
        String ingestToken = properties.getIngestToken();
        if (ingestToken != null && !ingestToken.isBlank() && ingestToken.equals(token)) {
            tenant = "default";
            role = "analyst";
            ingestCredential = true;
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
        } else if (ingestCredential) {
            String requestedTenant = request.getHeader("X-Tenant-Id");
            if (requestedTenant != null && !requestedTenant.isBlank() && !"default".equals(requestedTenant)) {
                throw ApiException.unauthorized("Static ingest credential is bound to the default tenant");
            }
        }

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
