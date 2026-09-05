package com.socp.soar.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.audit.api.AuditOperation;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.persistence.entity.SoarConnectorEntity;
import com.socp.soar.web.persistence.entity.PlaybookVersionEntity;
import com.socp.soar.web.persistence.repository.SoarConnectorRepository;
import com.socp.soar.web.persistence.repository.PlaybookVersionRepository;
import com.socp.soar.web.connector.ConnectionContext;
import com.socp.soar.web.connector.ConnectionTestResult;
import com.socp.soar.web.connector.EnvironmentSecretResolver;
import com.socp.soar.web.connector.SoarConnectorRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/** Connector catalog. Secrets are references only; endpoints are SSRF checked at write time. */
@Service
public class SoarV2ConnectorService {
    private final SoarConnectorRepository connectors;
    private final ObjectMapper mapper;
    private final SoarConnectorRegistry registry;
    private final EnvironmentSecretResolver secrets;
    private final PlaybookVersionRepository versions;

    @org.springframework.beans.factory.annotation.Autowired
    public SoarV2ConnectorService(SoarConnectorRepository connectors, ObjectMapper mapper,
                                   SoarConnectorRegistry registry, EnvironmentSecretResolver secrets,
                                   PlaybookVersionRepository versions) {
        this.connectors = connectors;
        this.mapper = mapper;
        this.registry = registry;
        this.secrets = secrets;
        this.versions = versions;
    }

    /** Compatibility constructor for production-shaped tests that wire the
     * connector runtime without the version repository. */
    public SoarV2ConnectorService(SoarConnectorRepository connectors, ObjectMapper mapper,
                                   SoarConnectorRegistry registry, EnvironmentSecretResolver secrets) {
        this(connectors, mapper, registry, secrets, null);
    }

    /** Compatibility constructor for focused unit tests that only exercise URL policy. */
    public SoarV2ConnectorService(SoarConnectorRepository connectors, ObjectMapper mapper) {
        this(connectors, mapper, null, new EnvironmentSecretResolver(), null);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_CREATE_CONNECTION", target = "t_soar_connector")
    public Map<String, Object> create(String name, String type, String endpoint, String secretRef,
                                      List<String> allowedHosts, boolean enabled) {
        String tenant = TenantContext.require();
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connector name is required (max 128)");
        }
        if (type == null || type.isBlank() || type.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connectorType is required (max 64)");
        }
        if (registry != null && registry.find(type).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connectorType is not registered");
        }
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connector endpoint is required (max 2048)");
        }
        if (allowedHosts == null || allowedHosts.isEmpty() || allowedHosts.size() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "allowedHosts must contain 1..32 entries");
        }
        validateAllowedHosts(allowedHosts);
        validateEndpoint(endpoint, allowedHosts);
        if (secretRef != null && !secretRef.isBlank() && !validSecretRef(secretRef)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authSecretRef must be a secret:// reference");
        }
        Instant now = Instant.now();
        SoarConnectorEntity row = new SoarConnectorEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setName(name.trim());
        row.setConnectorType(type.trim().toUpperCase());
        row.setEndpoint(endpoint.trim());
        row.setAuthSecretRef(secretRef == null ? null : secretRef.trim());
        row.setConfigJson("{}");
        row.setSecretRefsJson(secretRef == null ? "{}" : write(Map.of("auth", secretRef.trim())));
        row.setScopeJson("{}");
        row.setAllowedHostsJson(write(allowedHosts == null ? List.of() : allowedHosts));
        row.setEnabled(enabled);
        row.setStatus(enabled ? "HEALTHY_UNKNOWN" : "DISABLED");
        row.setRevision(1);
        row.setCreatedBy("operator");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setRowVersion(0L);
        connectors.save(row);
        return view(row);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return connectors.findByTenantIdOrderByNameAsc(TenantContext.require()).stream()
                .filter(row -> row.getDeletedAt() == null)
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(Pageable pageable) {
        List<SoarConnectorEntity> all = connectors.findByTenantIdOrderByNameAsc(TenantContext.require()).stream()
                .filter(row -> row.getDeletedAt() == null).toList();
        int from = Math.min(all.size(), Math.max(0, pageable.getPageNumber()) * pageable.getPageSize());
        int to = Math.min(all.size(), from + pageable.getPageSize());
        return new PageImpl<>(all.subList(from, to).stream().map(this::view).toList(), pageable, all.size());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        SoarConnectorEntity row = connectors.findByTenantIdAndId(TenantContext.require(), id)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found"));
        return view(row);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_SET_CONNECTION_ENABLED", target = "t_soar_connector")
    public Map<String, Object> setEnabled(String id, boolean enabled) {
        String tenant = TenantContext.require();
        SoarConnectorEntity row = findForUpdate(tenant, id);
        if (row.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found");
        }
        row.setEnabled(enabled);
        row.setStatus(enabled ? "HEALTHY_UNKNOWN" : "DISABLED");
        row.setUpdatedAt(Instant.now());
        connectors.save(row);
        return view(row);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_UPDATE_CONNECTION", target = "t_soar_connector")
    public Map<String, Object> update(String id, String name, String type, String endpoint,
                                      String secretRef, List<String> allowedHosts, boolean enabled) {
        return update(id, name, type, endpoint, secretRef, allowedHosts, enabled, null);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_UPDATE_CONNECTION", target = "t_soar_connector")
    public Map<String, Object> update(String id, String name, String type, String endpoint,
                                      String secretRef, List<String> allowedHosts, boolean enabled,
                                      Long expectedRowVersion) {
        String tenant = TenantContext.require();
        SoarConnectorEntity row = findForUpdate(tenant, id);
        if (row.getDeletedAt() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found");
        if (expectedRowVersion != null && !expectedRowVersion.equals(row.getRowVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "connector was changed by another operator");
        }
        if (name == null || name.isBlank() || name.length() > 128 || type == null || type.isBlank() || type.length() > 64
                || endpoint == null || endpoint.isBlank() || endpoint.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid connector fields");
        }
        if (registry != null && registry.find(type).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connectorType is not registered");
        }
        if (allowedHosts == null || allowedHosts.isEmpty() || allowedHosts.size() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "allowedHosts must contain 1..32 entries");
        }
        validateAllowedHosts(allowedHosts);
        validateEndpoint(endpoint, allowedHosts);
        if (secretRef != null && !secretRef.isBlank() && !validSecretRef(secretRef)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authSecretRef must be a secret:// reference");
        }
        row.setName(name.trim()); row.setConnectorType(type.trim().toUpperCase()); row.setEndpoint(endpoint.trim());
        // A missing secret field means "leave the existing reference" for
        // PUT/PATCH updates; an explicit empty string clears it.  The masked
        // representation returned by GET must never be sent back as a secret.
        if (secretRef != null) {
            row.setAuthSecretRef(secretRef.isBlank() ? null : secretRef.trim());
            row.setSecretRefsJson(secretRef.isBlank() ? "{}" : write(Map.of("auth", secretRef.trim())));
        }
        row.setAllowedHostsJson(write(allowedHosts)); row.setEnabled(enabled);
        row.setStatus(enabled ? "HEALTHY_UNKNOWN" : "DISABLED"); row.setRevision(Math.max(1, row.getRevision()) + 1);
        row.setUpdatedAt(Instant.now()); connectors.save(row);
        return view(row);
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_TEST_CONNECTION", target = "t_soar_connector")
    public Map<String, Object> test(String id) {
        SoarConnectorEntity row = connectors.findByTenantIdAndId(TenantContext.require(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found"));
        if (row.getDeletedAt() != null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found");
        if (registry == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "connector runtime is unavailable");
        ConnectionTestResult result = registry.test(row.getConnectorType(), connection(row));
        row.setLastTestAt(result.testedAt());
        row.setLastTestStatus(result.status());
        // Connector diagnostics are operator-visible and may contain a
        // vendor URL/body fragment. Keep the durable record under the same
        // redaction policy as run events and attempt errors.
        row.setLastTestError(redactFreeText(result.errorMessage(), 2048));
        row.setStatus(result.healthy() ? "HEALTHY" : "UNHEALTHY");
        row.setUpdatedAt(Instant.now());
        connectors.save(row);
        Map<String, Object> view = view(row);
        view.put("test", Map.of("healthy", result.healthy(), "status", result.status(),
                "durationMs", result.durationMs(), "details", result.details()));
        return view;
    }

    @Transactional
    @AuditOperation(action = "SOAR_V2_DELETE_CONNECTION", target = "t_soar_connector")
    public Map<String, Object> softDelete(String id) {
        String tenant = TenantContext.require();
        SoarConnectorEntity row = findForUpdate(tenant, id);
        if (row.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found");
        }
        if (isReferencedByPublishedVersion(tenant, row.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "connector is referenced by a published playbook version");
        }
        row.setDeletedAt(Instant.now());
        row.setEnabled(false);
        row.setStatus("DELETED");
        row.setUpdatedAt(Instant.now());
        connectors.save(row);
        return view(row);
    }

    /**
     * Published definitions are immutable snapshots.  Removing a connection
     * they reference would make an already approved run unreproducible, so a
     * connection must remain as a soft-deleted/disabled record until those
     * versions are deprecated.  The JSON scan is intentionally performed in
     * the service boundary so H2 and PostgreSQL share identical semantics.
     */
    private boolean isReferencedByPublishedVersion(String tenant, String connectionId) {
        if (versions == null || connectionId == null || connectionId.isBlank()) return false;
        for (PlaybookVersionEntity version : versions.findByTenantId(tenant)) {
            if (!"PUBLISHED".equalsIgnoreCase(version.getStatus())) continue;
            try {
                var root = mapper.readTree(version.getDefinitionJson());
                var nodes = root == null ? null : root.path("nodes");
                if (nodes != null && nodes.isArray()) {
                    for (var node : nodes) {
                        if (connectionId.equals(node.path("connectionRef").asText(""))) return true;
                    }
                }
            } catch (Exception ignored) {
                // A malformed historical row is not a reason to bypass the
                // safety check.  Fail closed so an operator must repair the
                // published row before deleting a referenced connection.
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> actions() {
        if (registry == null) return List.of();
        return registry.descriptors().stream().flatMap(connector -> connector.actions().stream().map(action -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("connectorId", connector.id());
            value.put("connectorVersion", connector.majorVersion());
            value.put("production", connector.production());
            value.put("actionRef", action.ref(connector.id()));
            value.put("id", action.id());
            value.put("displayName", action.displayName());
            value.put("description", action.description());
            value.put("riskLevel", action.riskLevel());
            value.put("sideEffect", action.sideEffect());
            value.put("idempotency", action.idempotency());
            value.put("requiresConnection", action.requiresConnection());
            value.put("requiredPermissions", action.requiredPermissions());
            value.put("requestTimeoutSeconds", action.requestTimeoutSeconds());
            value.put("retryCap", action.retryCap());
            value.put("payloadCapBytes", action.payloadCapBytes());
            value.put("sensitiveOutputFields", action.sensitiveOutputFields());
            value.put("supportsReconcile", action.supportsReconcile());
            value.put("supportsCompensate", action.supportsCompensate());
            value.put("inputSchema", action.inputSchema());
            value.put("outputSchema", action.outputSchema());
            return value;
        })).toList();
    }

    private Map<String, Object> view(SoarConnectorEntity row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId());
        result.put("name", row.getName());
        result.put("connectorType", row.getConnectorType());
        result.put("endpoint", row.getEndpoint());
        result.put("authSecretRef", row.getAuthSecretRef() == null || row.getAuthSecretRef().isBlank()
                ? "" : "[REFERENCE]");
        result.put("config", readObject(row.getConfigJson()));
        result.put("secretRefs", maskSecretRefs(row.getSecretRefsJson()));
        result.put("scope", readObject(row.getScopeJson()));
        result.put("allowedHosts", readList(row.getAllowedHostsJson()));
        result.put("enabled", row.isEnabled());
        result.put("status", row.getStatus() == null ? (row.isEnabled() ? "HEALTHY_UNKNOWN" : "DISABLED") : row.getStatus());
        result.put("revision", row.getRevision());
        result.put("lastTestAt", row.getLastTestAt());
        result.put("lastTestStatus", row.getLastTestStatus());
        result.put("lastTestError", redactFreeText(row.getLastTestError(), 2048));
        result.put("createdBy", row.getCreatedBy());
        result.put("createdAt", row.getCreatedAt());
        result.put("updatedAt", row.getUpdatedAt());
        result.put("rowVersion", row.getRowVersion());
        return result;
    }

    private static String redactFreeText(String value, int max) {
        if (value == null || value.isBlank()) return value;
        String redacted = value
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+", "$1[REDACTED]")
                .replaceAll("(?i)(\\b(?:secret|token|password|authorization|api[_-]?key|cookie)\\b\\s*[:=]\\s*)[^,;\\s}]+", "$1[REDACTED]");
        return redacted.length() <= max ? redacted : redacted.substring(0, max);
    }

    private SoarConnectorEntity findForUpdate(String tenant, String id) {
        java.util.Optional<SoarConnectorEntity> locked = connectors.findByTenantIdAndIdForUpdate(tenant, id);
        if (locked != null && locked.isPresent()) return locked.get();
        java.util.Optional<SoarConnectorEntity> ordinary = connectors.findByTenantIdAndId(tenant, id);
        if (ordinary == null || ordinary.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "connector not found");
        }
        return ordinary.get();
    }

    private ConnectionContext connection(SoarConnectorEntity row) {
        Map<String, String> refs = readStringMap(row.getSecretRefsJson());
        if (row.getAuthSecretRef() != null && !row.getAuthSecretRef().isBlank()) refs.putIfAbsent("auth", row.getAuthSecretRef());
        return new ConnectionContext(row.getTenantId(), row.getId(), Math.max(1, row.getRevision()),
                row.getConnectorType(), row.getEndpoint(), readObject(row.getConfigJson()), refs, secrets,
                java.time.Duration.ofSeconds(30), readList(row.getAllowedHostsJson()));
    }

    static void validateEndpoint(String endpoint, List<String> allowedHosts) {
        try {
            validateAllowedHosts(allowedHosts == null ? List.of() : allowedHosts);
            URI uri = URI.create(endpoint == null ? "" : endpoint.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || containsCredentialQuery(uri.getRawQuery())) {
                throw new IllegalArgumentException("connector endpoint must be an HTTPS URL without credentials");
            }
            String host = uri.getHost().toLowerCase();
            boolean allowed = allowedHosts != null && allowedHosts.stream().anyMatch(pattern -> hostMatches(host, pattern));
            if (!allowed || isPrivateLiteral(host)) {
                throw new IllegalArgumentException("connector endpoint host is not in the allowlist");
            }
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure.getMessage(), failure);
        }
    }

    private static void validateAllowedHosts(List<String> allowedHosts) {
        for (String raw : allowedHosts) {
            String host = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (host.isBlank() || host.length() > 255 || host.contains("/")
                    || host.contains("://") || host.equals("*")
                    || (host.startsWith("*.") && host.length() <= 2)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "allowedHosts contains an invalid host pattern");
            }
            if (host.startsWith("*.") && host.substring(2).indexOf('.') < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "wildcard allowlist must name a registrable domain");
            }
        }
    }

    private static boolean hostMatches(String host, String pattern) {
        if (pattern == null || pattern.isBlank()) return false;
        String value = pattern.trim().toLowerCase();
        return value.startsWith("*.") ? host.endsWith(value.substring(1)) : host.equals(value);
    }

    private static boolean containsCredentialQuery(String query) {
        return query != null && query.matches(
                "(?i).*(^|&)(?:secret|token|password|authorization|api[_-]?key|credential|cookie)[^=]*=.*");
    }

    private static boolean isPrivateLiteral(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("0.0.0.0")
                || host.equals("::1")) return true;
        try {
            // URI#getHost may return a bracketed IPv6 literal.  Normalize it
            // before asking the JDK so loopback/link-local IPv6 cannot bypass
            // the old IPv4-only literal check.
            String literal = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            if (!literal.matches("[0-9a-fA-F:.]+")) return false;
            if (!literal.matches("[0-9.]+") && !literal.contains(":")) return false;
            InetAddress address = InetAddress.getByName(literal);
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress();
        } catch (Exception ignored) { return true; }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalArgumentException("cannot serialize connector data", failure); }
    }

    private List<String> readList(String value) {
        try { return mapper.readValue(value == null ? "[]" : value, mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class)); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, Object> readObject(String value) {
        try { return mapper.readValue(value == null ? "{}" : value,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception ignored) { return Map.of(); }
    }

    private Map<String, String> readStringMap(String value) {
        try { return new LinkedHashMap<>(mapper.readValue(value == null ? "{}" : value,
                mapper.getTypeFactory().constructMapType(Map.class, String.class, String.class))); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private Map<String, Object> maskSecretRefs(String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        readStringMap(value).forEach((key, ref) -> out.put(key, ref == null ? "" : "[REFERENCE]"));
        return out;
    }

    private static boolean validSecretRef(String value) {
        return value != null && value.matches("secret://[A-Za-z_][A-Za-z0-9_./-]{0,254}");
    }
}
