package com.socp.soar.web.connector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.service.SearchClient;
import com.socp.platform.client.service.SocpService;
import com.socp.platform.client.service.ThreatClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The controlled built-in connector registry. Connector implementations are
 * compiled into the service; arbitrary JAR/ZIP uploads are deliberately not
 * supported. The registry is the only place where action refs become code.
 */
@Component
public class SoarConnectorRegistry {
    private final Map<String, SoarConnector> connectors;

    public SoarConnectorRegistry(AlertClient alert, IncidentClient incident,
                                 NotifyClient notify, SearchClient search,
                                 ThreatClient threat, SocpHttpClient http,
                                 EnvironmentSecretResolver secrets, ObjectMapper mapper) {
        Map<String, SoarConnector> values = new LinkedHashMap<>();
        values.put("socp.alert", service("socp.alert", "SOCP Alert", true,
                List.of(action("get", "Get alert", "READ_ONLY", "NONE", "NONE", false),
                        // Native SOCP clients use the service identity and the
                        // active tenant context; they are not external assets
                        // and therefore do not require a user-configured
                        // connectionRef.
                        action("add-note", "Add note", "LOW", "IRREVERSIBLE", "NATIVE", false),
                        action("assign", "Assign alert", "MEDIUM", "REVERSIBLE", "NATIVE", false),
                        action("set-status", "Set status", "MEDIUM", "REVERSIBLE", "NATIVE", false),
                        action("add-tag", "Add tag", "LOW", "REVERSIBLE", "NATIVE", false)),
                (ref, request) -> executeAlert(alert, ref, request)));
        values.put("socp.incident", service("socp.incident", "SOCP Incident", true,
                List.of(action("get", "Get incident", "READ_ONLY", "NONE", "NONE", false),
                        action("create", "Create incident", "MEDIUM", "IRREVERSIBLE", "NATIVE", false),
                        action("append-timeline", "Append timeline", "LOW", "IRREVERSIBLE", "NATIVE", false),
                        action("assign", "Assign incident", "MEDIUM", "REVERSIBLE", "NATIVE", false),
                        action("set-status", "Set incident status", "MEDIUM", "REVERSIBLE", "NATIVE", false),
                        action("add-task", "Add case task", "LOW", "IRREVERSIBLE", "NATIVE", false),
                        action("complete-task", "Complete case task", "LOW", "REVERSIBLE", "NATIVE", false)),
                (ref, request) -> executeIncident(incident, ref, request)));
        values.put("socp.search", service("socp.search", "SOCP Search", true,
                List.of(action("search-events", "Search events", "READ_ONLY", "NONE", "NONE", false),
                        action("get-event", "Get event", "READ_ONLY", "NONE", "NONE", false)),
                (ref, request) -> executeSearch(search, ref, request)));
        values.put("socp.asset", service("socp.asset", "SOCP Asset", true,
                List.of(action("find-by-entity", "Find asset by entity", "READ_ONLY", "NONE", "NONE", false),
                        action("get-asset", "Get asset", "READ_ONLY", "NONE", "NONE", false)),
                (ref, request) -> executeAsset(http, mapper, ref, request)));
        values.put("socp.threat-intel", service("socp.threat-intel", "SOCP Threat Intelligence", true,
                List.of(action("lookup-ioc", "Lookup IOC", "READ_ONLY", "NONE", "NONE", false)),
                (ref, request) -> executeThreat(threat, ref, request)));
        values.put("socp.notify", service("socp.notify", "SOCP Notify", true,
                List.of(action("send-channel", "Send notification", "MEDIUM", "IRREVERSIBLE", "NATIVE", false)),
                (ref, request) -> executeNotify(notify, request)));
        values.put("http.webhook", new HttpWebhookConnector(http, secrets, mapper));
        values.put("endpoint", new ConfiguredExternalConnector("endpoint", "Endpoint Response", http, secrets,
                List.of(action("isolate-host", "Isolate host", "HIGH", "REVERSIBLE", "NATIVE", true),
                        action("release-host", "Release host", "HIGH", "REVERSIBLE", "NATIVE", true),
                        action("snapshot", "Snapshot endpoint", "HIGH", "IRREVERSIBLE", "NATIVE", true))));
        values.put("firewall", new ConfiguredExternalConnector("firewall", "Firewall Response", http, secrets,
                List.of(action("block-ioc", "Block IOC", "HIGH", "REVERSIBLE", "NATIVE", true),
                        action("unblock-ioc", "Unblock IOC", "HIGH", "REVERSIBLE", "NATIVE", true))));
        this.connectors = Map.copyOf(values);
    }

    public List<ConnectorDescriptor> descriptors() {
        return connectors.values().stream().map(SoarConnector::descriptor)
                .sorted(java.util.Comparator.comparing(ConnectorDescriptor::id)).toList();
    }

    public Optional<SoarConnector> find(String connectorId) {
        String id = normalize(connectorId);
        if ("net.firewall".equals(id)) id = "firewall";
        return Optional.ofNullable(connectors.get(id));
    }

    public Optional<ConnectorDescriptor> descriptorForAction(String actionRef) {
        actionRef = canonicalRef(actionRef);
        int requestedVersion = requestedVersion(actionRef);
        String[] parsed = parseRef(actionRef);
        if (parsed == null) return Optional.empty();
        SoarConnector connector = connectors.get(parsed[0]);
        if (connector == null) return Optional.empty();
        if (requestedVersion > 0 && requestedVersion != connector.descriptor().majorVersion()) return Optional.empty();
        return connector.descriptor().actions().stream()
                .filter(a -> a.id().equals(parsed[1]))
                .findFirst().map(ignore -> connector.descriptor());
    }

    /** Returns the concrete {@link ActionDescriptor} for an action ref, or
     * empty when the ref is unknown. */
    public Optional<ActionDescriptor> actionDescriptor(String actionRef) {
        String canonical = canonicalRef(actionRef);
        String[] parsed = parseRef(canonical);
        if (parsed == null) return Optional.empty();
        SoarConnector connector = connectors.get(parsed[0]);
        if (connector == null) return Optional.empty();
        return connector.descriptor().actions().stream()
                .filter(action -> action.id().equals(parsed[1]))
                .findFirst();
    }

    /** Returns the stable built-in spelling used for execution and policy checks. */
    public String canonicalActionRef(String actionRef) {
        return canonicalRef(actionRef);
    }

    public ActionResult execute(ActionRequest request) {
        String canonical = canonicalRef(request.actionRef());
        String[] parsed = parseRef(canonical);
        if (parsed == null) return ActionResult.failed("SOAR_ACTION_NOT_FOUND", "invalid action ref", false);
        SoarConnector connector = connectors.get(parsed[0]);
        if (connector == null) return ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unknown connector", false);
        if (requestedVersion(canonical) > 0 && requestedVersion(canonical) != connector.descriptor().majorVersion()) {
            return ActionResult.failed("SOAR_ACTION_VERSION_UNAVAILABLE", "unsupported connector major version", false);
        }
        boolean known = connector.descriptor().actions().stream().anyMatch(a -> a.id().equals(parsed[1]));
        if (!known) return ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unknown action", false);
        try {
            return connector.execute(new ActionRequest(request.tenantId(), request.runId(), request.nodeRunId(),
                    request.attemptNo(), canonical, request.idempotencyKey(), request.parameters(),
                    request.target(), request.connection()));
        } catch (RuntimeException failure) {
            return ActionResult.failed(errorCode(failure, "CONNECTOR_EXCEPTION"), safe(failure.getMessage()), true);
        }
    }

    /**
     * Ask a connector whether an UNKNOWN result can be proven by its read
     * API. An empty result is intentional: callers must keep ACTION_UNKNOWN
     * and request operator evidence instead of guessing.
     */
    public Optional<ActionResult> reconcile(ActionQuery query) {
        if (query == null) return Optional.empty();
        String canonical = canonicalRef(query.actionRef());
        String[] parsed = parseRef(canonical);
        if (parsed == null) return Optional.empty();
        SoarConnector connector = connectors.get(parsed[0]);
        if (connector == null) return Optional.empty();
        try {
            return connector.reconcile(new ActionQuery(query.tenantId(), query.runId(), query.nodeRunId(),
                    canonical, query.idempotencyKey(), query.target(), query.parameters()));
        } catch (RuntimeException ignored) {
            // Reconcile is advisory. A provider outage is not evidence of
            // either a successful or an unexecuted write.
            return Optional.empty();
        }
    }

    /** Execute a declared compensation action through the registry boundary. */
    public Optional<ActionResult> compensate(ActionRequest request, String compensationRef) {
        if (request == null || compensationRef == null || compensationRef.isBlank()) return Optional.empty();
        String canonical = canonicalRef(compensationRef);
        String[] parsed = parseRef(canonical);
        if (parsed == null) return Optional.empty();
        SoarConnector connector = connectors.get(parsed[0]);
        if (connector == null || connector.descriptor().actions().stream()
                .noneMatch(action -> action.id().equals(parsed[1]))) return Optional.empty();
        try {
            ActionRequest compensation = new ActionRequest(request.tenantId(), request.runId(),
                    request.nodeRunId(), request.attemptNo(), canonical, request.idempotencyKey(),
                    request.parameters(), request.target(), request.connection());
            return connector.compensate(compensation);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public ConnectionTestResult test(String connectorId, ConnectionContext context) {
        return find(connectorId).map(c -> {
            try { return c.test(context); }
            catch (RuntimeException failure) {
                return ConnectionTestResult.failed(errorCode(failure, "CONNECTOR_EXCEPTION"), safe(failure.getMessage()), 0);
            }
        }).orElseGet(() -> ConnectionTestResult.failed("SOAR_ACTION_NOT_FOUND", "unknown connector", 0));
    }

    private static SoarConnector service(String id, String name, boolean production,
                                         List<ActionDescriptor> actions,
                                         BiFunction<String, ActionRequest, ActionResult> executor) {
        ConnectorDescriptor descriptor = new ConnectorDescriptor(id, 1, name, production, actions);
        return new SoarConnector() {
            @Override public ConnectorDescriptor descriptor() { return descriptor; }
            @Override public ConnectionTestResult test(ConnectionContext connection) {
                return ConnectionTestResult.ok(0, Map.of("connector", id));
            }
            @Override public ActionResult execute(ActionRequest request) {
                String[] parsed = parseRef(request.actionRef());
                return executor.apply(parsed == null ? "" : parsed[1], request);
            }
        };
    }

    private static ActionDescriptor action(String id, String display, String risk, boolean sideEffect) {
        return action(id, display, risk, sideEffect ? "REVERSIBLE" : "NONE",
                sideEffect ? "NATIVE" : "NONE", false);
    }

    private static ActionDescriptor action(String id, String display, String risk, boolean sideEffect,
                                           boolean requiresConnection) {
        return action(id, display, risk, sideEffect ? "REVERSIBLE" : "NONE",
                sideEffect ? "NATIVE" : "NONE", requiresConnection);
    }

    private static ActionDescriptor action(String id, String display, String risk,
                                           String sideEffect, String idempotency,
                                           boolean requiresConnection) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("properties", inputProperties(id));
        schema.put("title", display + " input");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("type", "object");
        output.put("additionalProperties", true);
        output.put("properties", Map.of(
                "status", Map.of("type", "string"),
                "operationId", Map.of("type", "string"),
                "receipt", Map.of("type", "object"),
                "data", Map.of("type", "object")));
        List<String> permissions = "HIGH".equalsIgnoreCase(risk) || "CRITICAL".equalsIgnoreCase(risk)
                ? List.of("soar:execute", "soar:approve") : List.of("soar:execute");
        return new ActionDescriptor(id, 1, display, display, risk,
                sideEffect, idempotency,
                requiresConnection, List.of("alert", "incident", "entity", "host", "endpoint"), schema, output,
                permissions);
    }

    /** Action-specific fields are advertised to the editor and contract
     * tooling. Additional context fields remain allowed because Workflow
     * variables are intentionally namespaced and resolved at execution time. */
    private static Map<String, Object> inputProperties(String actionId) {
        Map<String, Object> properties = new LinkedHashMap<>();
        switch (actionId) {
            case "get" -> {
                properties.put("alertId", Map.of("type", "string"));
                properties.put("incidentId", Map.of("type", "string"));
                properties.put("assetId", Map.of("type", "string"));
                properties.put("eventId", Map.of("type", "string"));
            }
            case "add-note", "append-timeline" -> {
                properties.put("alertId", Map.of("type", "string"));
                properties.put("incidentId", Map.of("type", "string"));
                properties.put("content", Map.of("type", "string", "maxLength", 16_384));
            }
            case "assign" -> {
                properties.put("alertId", Map.of("type", "string"));
                properties.put("incidentId", Map.of("type", "string"));
                properties.put("assignee", Map.of("type", "string", "maxLength", 255));
            }
            case "set-status" -> {
                properties.put("alertId", Map.of("type", "string"));
                properties.put("incidentId", Map.of("type", "string"));
                properties.put("status", Map.of("type", "string", "maxLength", 64));
                properties.put("assignee", Map.of("type", "string", "maxLength", 255));
            }
            case "add-tag" -> {
                properties.put("alertId", Map.of("type", "string"));
                properties.put("tag", Map.of("type", "string", "maxLength", 128));
            }
            case "create" -> {
                properties.put("title", Map.of("type", "string", "maxLength", 512));
                properties.put("description", Map.of("type", "string", "maxLength", 16_384));
                properties.put("alertId", Map.of("type", "string"));
            }
            case "add-task", "complete-task" -> {
                properties.put("incidentId", Map.of("type", "string"));
                properties.put("taskId", Map.of("type", "string"));
                properties.put("content", Map.of("type", "string", "maxLength", 16_384));
            }
            case "search-events" -> {
                properties.put("query", Map.of("type", "string", "maxLength", 4_096));
                properties.put("expression", Map.of("type", "string", "maxLength", 4_096));
            }
            case "find-by-entity" -> {
                properties.put("entity", Map.of("type", "string", "maxLength", 512));
                properties.put("assetId", Map.of("type", "string"));
            }
            case "get-asset" -> properties.put("assetId", Map.of("type", "string"));
            case "lookup-ioc" -> {
                properties.put("ioc", Map.of("type", "string", "maxLength", 2_048));
                properties.put("value", Map.of("type", "string", "maxLength", 2_048));
            }
            case "send-channel" -> {
                properties.put("channelId", Map.of("type", "string", "maxLength", 255));
                properties.put("message", Map.of("type", "string", "maxLength", 16_384));
                properties.put("content", Map.of("type", "string", "maxLength", 16_384));
            }
            case "request", "isolate-host", "release-host", "snapshot", "block-ioc", "unblock-ioc" -> {
                properties.put("method", Map.of("type", "string", "maxLength", 16));
                properties.put("body", Map.of("type", "object"));
                properties.put("target", Map.of("type", "object"));
            }
            default -> { }
        }
        return properties;
    }

    private static ActionResult executeAlert(AlertClient client, String action, ActionRequest request) {
        String id = text(request.parameters(), "alertId", text(request.target(), "id", ""));
        ServiceCall call = switch (action) {
            case "get" -> client.getAlarm(id);
            case "add-note" -> client.addNote(id, "soar",
                    text(request.parameters(), "content", "SOAR note"), request.idempotencyKey());
            case "assign" -> client.assign(id, text(request.parameters(), "assignee", "soar"),
                    request.idempotencyKey());
            case "set-status" -> client.setStatus(id, text(request.parameters(), "status", "INVESTIGATING"),
                    request.idempotencyKey());
            case "add-tag" -> client.addTag(id, text(request.parameters(), "tag", "soar"),
                    request.idempotencyKey());
            default -> null;
        };
        if (call == null) return ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unsupported alert action", false);
        return fromCall(call, action, !"get".equals(action));
    }

    private static ActionResult executeIncident(IncidentClient client, String action, ActionRequest request) {
        String id = text(request.parameters(), "incidentId", text(request.target(), "id", ""));
        ServiceCall call = switch (action) {
            case "get" -> client.list();
            case "append-timeline" -> client.addNote(id, "soar", text(request.parameters(), "content", "SOAR timeline update"), request.idempotencyKey());
            case "create" -> client.createFromAlarm(json(request.parameters()), request.idempotencyKey());
            case "assign" -> client.setStatus(id, "INVESTIGATING", text(request.parameters(), "assignee", "soar"));
            case "set-status" -> client.setStatus(id, text(request.parameters(), "status", "INVESTIGATING"),
                    text(request.parameters(), "assignee", ""));
            case "add-task", "complete-task" -> client.addNote(id, "soar",
                    text(request.parameters(), "content", "SOAR case task: " + action), request.idempotencyKey());
            default -> null;
        };
        if (call == null) return ActionResult.failed("SOAR_ACTION_NOT_FOUND", "unsupported incident action", false);
        return fromCall(call, action, "get".equals(action));
    }

    private static ActionResult executeSearch(SearchClient client, String action, ActionRequest request) {
        String query = text(request.parameters(), "query", text(request.parameters(), "expression", ""));
        return fromCall(client.search(query), action, false);
    }

    private static ActionResult executeThreat(ThreatClient client, String action, ActionRequest request) {
        Object ioc = request.parameters().getOrDefault("ioc", request.parameters().get("value"));
        return fromCall(client.matchIocs(json(ioc instanceof List<?> ? ioc : List.of(String.valueOf(ioc)))), action, true);
    }

    private static ActionResult executeNotify(NotifyClient client, ActionRequest request) {
        return fromCall(client.notifyAlert(json(request.parameters()), request.idempotencyKey()), "send-channel", true);
    }

    private static ActionResult executeAsset(SocpHttpClient http, ObjectMapper mapper,
                                             String action, ActionRequest request) {
        // asset-web intentionally exposes a tenant-scoped collection endpoint,
        // not the guessed /find/by/entity and /get/asset paths used by the old
        // shell implementation.  Fetch the authoritative collection once and
        // perform the selector locally so the action has deterministic,
        // auditable read semantics without inventing an API that does not exist.
        ServiceCall call = http.get(SocpService.ASSET, "/api/v1/assets");
        if (call == null) return ActionResult.failed("SERVICE_NO_RESULT", "service returned no result", true);
        if (!call.ok()) return ActionResult.failed("SERVICE_CALL_FAILED", safe(call.failureReason()), call.retryable());
        try {
            JsonNode payload = mapper.readTree(call.body() == null ? "[]" : call.body());
            JsonNode items = payload != null && payload.isArray() ? payload
                    : payload == null ? null : payload.path("items");
            if (items == null || !items.isArray()) {
                return ActionResult.failed("MISSING_CONNECTOR_RECEIPT", "asset service returned no collection", false);
            }
            String selector = text(request.parameters(), "assetId",
                    text(request.target(), "id", text(request.parameters(), "entity",
                            text(request.target(), "entity", ""))));
            List<Map<String, Object>> matches = new ArrayList<>();
            int inspected = 0;
            for (JsonNode item : items) {
                if (item == null || !item.isObject() || inspected++ >= 2000) break;
                Map<String, Object> candidate = mapper.convertValue(item, new TypeReference<>() { });
                if (selector.isBlank() || matchesAsset(action, selector, candidate)) matches.add(candidate);
                if ("get-asset".equals(action) && !matches.isEmpty()) break;
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("matches", matches);
            output.put("count", matches.size());
            output.put("inspected", Math.min(inspected, 2000));
            output.put("truncated", inspected >= 2000);
            return ActionResult.success(matches.size() == 1 ? text(matches.get(0), "id", "") : "",
                    output, Map.of("action", action, "httpStatus", call.status(), "count", matches.size()));
        } catch (Exception failure) {
            return ActionResult.failed("SERVICE_RESPONSE_INVALID", safe(failure.getMessage()), false);
        }
    }

    private static boolean matchesAsset(String action, String selector, Map<String, Object> asset) {
        if ("get-asset".equals(action)) {
            return selector.equals(String.valueOf(asset.getOrDefault("id", "")));
        }
        String expected = selector.trim().toLowerCase(Locale.ROOT);
        for (String field : List.of("id", "name", "ip", "owner", "type", "os")) {
            Object value = asset.get(field);
            if (value != null && expected.equals(String.valueOf(value).trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static ActionResult fromCall(ServiceCall call, String action, boolean requireBody) {
        if (call == null) return ActionResult.failed("SERVICE_NO_RESULT", "service returned no result", true);
        if (!call.ok()) return ActionResult.failed("SERVICE_CALL_FAILED", safe(call.failureReason()), call.retryable());
        if (requireBody && (call.body() == null || call.body().isBlank())) {
            return ActionResult.failed("MISSING_CONNECTOR_RECEIPT", "missing response body", false);
        }
        Map<String, Object> body = parse(call.body());
        if (requireBody && body.isEmpty()) {
            return ActionResult.failed("MISSING_CONNECTOR_RECEIPT",
                    "response did not contain a verifiable receipt", false);
        }
        String operation = text(body, "operationId", text(body, "id", null));
        return ActionResult.success(operation, body, Map.of("action", action, "httpStatus", call.status()));
    }

    private static ActionResult fromExternalCall(ServiceCall call, String action) {
        if (call != null && !call.ok() && call.status() < 0) {
            return ActionResult.unknown("REMOTE_RESULT_UNKNOWN", safe(call.failureReason()));
        }
        ActionResult result = fromCall(call, action, true);
        if ("SUCCEEDED".equalsIgnoreCase(result.status())
                && (result.operationId() == null || result.operationId().isBlank())
                && !hasVerifiableReceipt(result.output())) {
            // A generic endpoint is not allowed to turn an HTTP 2xx into a
            // successful response action unless it returns a business
            // receipt/operation handle that can be audited or reconciled.
            return ActionResult.failed("MISSING_CONNECTOR_RECEIPT",
                    "external connector response did not contain an operation or receipt", false);
        }
        return result;
    }

    private static boolean hasVerifiableReceipt(Map<String, Object> body) {
        if (body == null || body.isEmpty()) return false;
        for (String key : List.of("receipt", "operation", "operation_id", "requestId", "request_id",
                "taskId", "task_id", "jobId", "job_id")) {
            Object value = body.get(key);
            if (value instanceof Map<?, ?> map && !map.isEmpty()) return true;
            if (value != null && !String.valueOf(value).isBlank()) return true;
        }
        return false;
    }

    private static final class HttpWebhookConnector implements SoarConnector {
        private final ConnectorDescriptor descriptor;
        private final SocpHttpClient http;
        private final SecretResolver secrets;
        private final ObjectMapper mapper;

        private HttpWebhookConnector(SocpHttpClient http, SecretResolver secrets, ObjectMapper mapper) {
            this.http = http; this.secrets = secrets; this.mapper = mapper;
            this.descriptor = new ConnectorDescriptor("http.webhook", 1, "HTTP Webhook", true,
                    List.of(action("request", "HTTPS request", "MEDIUM", "IRREVERSIBLE", "NONE", true)));
        }
        @Override public ConnectorDescriptor descriptor() { return descriptor; }
        @Override public ConnectionTestResult test(ConnectionContext connection) {
            long start = System.nanoTime();
            if (connection == null || connection.endpoint() == null || connection.endpoint().isBlank())
                return ConnectionTestResult.failed("SOAR_CONNECTION_UNAVAILABLE", "endpoint is required", 0);
            ServiceCall call = http.postExternal(connection.endpoint(), "{}", SocpHttpClient.JSON,
                    (int) Math.min(30_000, connection.timeout().toMillis()), authHeaders(connection), connection.allowedHosts());
            return call.ok() ? ConnectionTestResult.ok(elapsed(start), Map.of("status", call.status()))
                    : ConnectionTestResult.failed("SOAR_EGRESS_DENIED", safe(call.failureReason()), elapsed(start));
        }
        @Override public ActionResult execute(ActionRequest request) {
            ConnectionContext connection = request.connection();
            if (connection == null) return ActionResult.failed("SOAR_CONNECTION_UNAVAILABLE", "connection is required", false);
            String body = json(request.parameters());
            ServiceCall call = http.postExternal(connection.endpoint(), body, SocpHttpClient.JSON,
                    (int) Math.min(60_000, connection.timeout().toMillis()),
                    requestHeaders(connection, request.idempotencyKey()), connection.allowedHosts());
            return fromExternalCall(call, "request");
        }
    }

    private static final class ConfiguredExternalConnector implements SoarConnector {
        private final ConnectorDescriptor descriptor;
        private final SocpHttpClient http;
        private final SecretResolver secrets;
        private final String id;
        private ConfiguredExternalConnector(String id, String name, SocpHttpClient http,
                                            SecretResolver secrets, List<ActionDescriptor> actions) {
            this.id = id; this.http = http; this.secrets = secrets;
            this.descriptor = new ConnectorDescriptor(id, 1, name, false, actions);
        }
        @Override public ConnectorDescriptor descriptor() { return descriptor; }
        @Override public ConnectionTestResult test(ConnectionContext connection) {
            if (connection == null || connection.endpoint() == null || connection.endpoint().isBlank())
                return ConnectionTestResult.failed("SOAR_CONNECTION_UNAVAILABLE", "endpoint is required", 0);
            long start = System.nanoTime();
            ServiceCall call = http.postExternal(connection.endpoint(), "{}", SocpHttpClient.JSON,
                    (int) Math.min(30_000, connection.timeout().toMillis()), authHeaders(connection), connection.allowedHosts());
            return call.ok() ? ConnectionTestResult.ok(elapsed(start), Map.of("status", call.status()))
                    : ConnectionTestResult.failed("SOAR_EGRESS_DENIED", safe(call.failureReason()), elapsed(start));
        }
        @Override public ActionResult execute(ActionRequest request) {
            if (request.connection() == null) return ActionResult.failed("SOAR_CONNECTION_UNAVAILABLE", "connection is required", false);
            ServiceCall call = http.postExternal(request.connection().endpoint(), json(request.parameters()),
                    SocpHttpClient.JSON, (int) Math.min(60_000, request.connection().timeout().toMillis()),
                    requestHeaders(request.connection(), request.idempotencyKey()), request.connection().allowedHosts());
            return fromExternalCall(call, id);
        }
    }

    private static Map<String, String> authHeaders(ConnectionContext connection) {
        String token = connection.resolveSecret("auth");
        if (token == null || token.isBlank()) return Map.of();
        String value = token.regionMatches(true, 0, "Bearer ", 0, 7) ? token : "Bearer " + token;
        return Map.of("Authorization", value);
    }

    private static Map<String, String> requestHeaders(ConnectionContext connection, String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>(authHeaders(connection));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private static String[] parseRef(String actionRef) {
        if (actionRef == null) return null;
        String value = actionRef.trim().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(0, at);
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) return null;
        return new String[]{value.substring(0, slash), value.substring(slash + 1)};
    }

    private static int requestedVersion(String actionRef) {
        if (actionRef == null) return -1;
        int at = actionRef.lastIndexOf('@');
        if (at < 0 || at == actionRef.length() - 1) return -1;
        try { return Integer.parseInt(actionRef.substring(at + 1).replaceFirst("^v", "")); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String normalize(String id) { return id == null ? "" : id.trim().toLowerCase(Locale.ROOT); }
    private static String canonicalRef(String ref) {
        String value = ref == null ? "" : ref.trim();
        if (value.equalsIgnoreCase("http/webhook") || value.startsWith("http/webhook@")) {
            return value.replaceFirst("(?i)^http/webhook", "http.webhook/request");
        }
        if (value.equalsIgnoreCase("net.firewall/block") || value.startsWith("net.firewall/block@")) {
            return value.replaceFirst("(?i)^net\\.firewall/block", "firewall/block-ioc");
        }
        if (value.equalsIgnoreCase("socp.notify/send") || value.startsWith("socp.notify/send@")) {
            return value.replaceFirst("(?i)^socp\\.notify/send", "socp.notify/send-channel");
        }
        if (value.equalsIgnoreCase("socp.threat-intel/ioc.lookup") || value.startsWith("socp.threat-intel/ioc.lookup@")) {
            return value.replaceFirst("(?i)^socp\\.threat-intel/ioc\\.lookup", "socp.threat-intel/lookup-ioc");
        }
        if (value.equalsIgnoreCase("endpoint/isolate") || value.startsWith("endpoint/isolate@")) {
            return value.replaceFirst("(?i)^endpoint/isolate", "endpoint/isolate-host");
        }
        if (value.equalsIgnoreCase("endpoint/snapshot-host") || value.startsWith("endpoint/snapshot-host@")) {
            return value.replaceFirst("(?i)^endpoint/snapshot-host", "endpoint/snapshot");
        }
        return value;
    }
    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
    private static String json(Object value) {
        try { return new ObjectMapper().writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception ignored) { return "{}"; }
    }
    private static Map<String, Object> parse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try { return new ObjectMapper().readValue(body, new TypeReference<>() { }); }
        catch (Exception ignored) { return Map.of("raw", body.length() > 4096 ? body.substring(0, 4096) : body); }
    }
    private static String safe(String text) { return text == null ? "connector call failed" : text.substring(0, Math.min(1024, text.length())); }
    private static String errorCode(RuntimeException failure, String fallback) {
        String message = failure == null ? "" : failure.getMessage();
        return message != null && message.startsWith("SOAR_SECRET_RESOLUTION_FAILED")
                ? "SOAR_SECRET_RESOLUTION_FAILED" : fallback;
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000L; }
}
