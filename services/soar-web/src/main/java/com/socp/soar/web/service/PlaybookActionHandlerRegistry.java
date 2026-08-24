package com.socp.soar.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SocpHttpClient;
import com.socp.soar.web.model.PlaybookActionStatus;
import com.socp.soar.web.model.PlaybookActionType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed SOAR action handler registry.
 *
 * <p>A new action family must register a handler here. There is deliberately no
 * fallback handler: the executor turns an absent registration into FAILED.</p>
 */
@Component
public class PlaybookActionHandlerRegistry {

    private static final int WEBHOOK_TIMEOUT_MS = 3000;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EnumMap<PlaybookActionType, PlaybookActionHandler> handlers =
            new EnumMap<>(PlaybookActionType.class);

    public PlaybookActionHandlerRegistry(NotifyClient notifyClient,
                                         IncidentClient incidentClient,
                                         SocpHttpClient http) {
        register(new WebhookHandler(http));
        register(new NotifyHandler(notifyClient));
        register(new CaseHandler(incidentClient));
        register(new SimulatedHandler(PlaybookActionType.TAG));
        register(new SimulatedHandler(PlaybookActionType.SIMULATED));
    }

    private void register(PlaybookActionHandler handler) {
        PlaybookActionHandler previous = handlers.put(handler.type(), handler);
        if (previous != null) {
            throw new IllegalStateException("duplicate SOAR action handler: " + handler.type());
        }
    }

    public PlaybookActionHandler find(PlaybookActionType type) {
        return handlers.get(type);
    }

    private static final class WebhookHandler implements PlaybookActionHandler {
        private final SocpHttpClient http;

        private WebhookHandler(SocpHttpClient http) {
            this.http = http;
        }

        @Override
        public PlaybookActionType type() {
            return PlaybookActionType.WEBHOOK;
        }

        @Override
        public Map<String, Object> handle(PlaybookActionContext context) {
            String url = context.action();
            String ssrfError = validateSsrf(url);
            if (ssrfError != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", PlaybookActionStatus.FAILED.wireValue());
                result.put("mode", "BLOCKED");
                result.put("verified", false);
                result.put("errorCode", "SSRF_BLOCKED");
                result.put("error", ssrfError);
                return result;
            }
            ServiceCall call = http.postExternal(url, context.payloadJson(),
                    SocpHttpClient.JSON, WEBHOOK_TIMEOUT_MS);
            return verifiedCall("webhook", call, false);
        }

        private static String validateSsrf(String rawUrl) {
            if (rawUrl == null || rawUrl.isBlank()) return "webhook URL is empty";
            try {
                java.net.URI uri = java.net.URI.create(rawUrl.trim());
                String scheme = uri.getScheme();
                if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                    return "invalid webhook scheme: " + scheme + " (only HTTP/HTTPS allowed)";
                }
                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return "invalid webhook host";
                }
                if ("169.254.169.254".equals(host) || "255.255.255.255".equals(host) || "0.0.0.0".equals(host)) {
                    return "access to cloud metadata or link-local IP is blocked: " + host;
                }
                return null;
            } catch (Exception ex) {
                return "invalid webhook URL format: " + ex.getMessage();
            }
        }
    }

    private static final class NotifyHandler implements PlaybookActionHandler {
        private final NotifyClient client;

        private NotifyHandler(NotifyClient client) {
            this.client = client;
        }

        @Override
        public PlaybookActionType type() {
            return PlaybookActionType.NOTIFY;
        }

        @Override
        public Map<String, Object> handle(PlaybookActionContext context) {
            ServiceCall call = client.notifyAlert(context.payloadJson());
            Map<String, Object> result = verifiedCall("notify-web", call, false);
            if (PlaybookActionStatus.SUCCESS.wireValue().equals(result.get("status"))) {
                Map<String, Object> response = jsonObject(call.body());
                Object failed = response.get("failed");
                if (!(failed instanceof Number number)) {
                    result.put("status", PlaybookActionStatus.FAILED.wireValue());
                    result.put("mode", "EXECUTED");
                    result.put("verified", false);
                    result.put("errorCode", "MISSING_DELIVERY_RECEIPT");
                    result.put("error", "notify-web returned 2xx without a numeric failed count");
                } else if (number.intValue() > 0) {
                    result.put("status", PlaybookActionStatus.FAILED.wireValue());
                    result.put("mode", "EXECUTED");
                    result.put("verified", false);
                    result.put("errorCode", "DOWNSTREAM_DELIVERY_FAILED");
                    result.put("error", "notify-web reported failed deliveries: " + number.intValue());
                } else {
                    result.put("verified", true);
                    result.put("verification", "http-2xx-and-no-downstream-failures");
                }
            }
            return result;
        }
    }

    private static final class CaseHandler implements PlaybookActionHandler {
        private final IncidentClient client;

        private CaseHandler(IncidentClient client) {
            this.client = client;
        }

        @Override
        public PlaybookActionType type() {
            return PlaybookActionType.CASE;
        }

        @Override
        public Map<String, Object> handle(PlaybookActionContext context) {
            ServiceCall call = client.createFromAlarm(context.payloadJson());
            Map<String, Object> result = verifiedCall("incident-web", call, false);
            if (PlaybookActionStatus.SUCCESS.wireValue().equals(result.get("status"))) {
                Map<String, Object> response = jsonObject(call.body());
                Object caseId = response.get("caseId");
                if (!(caseId instanceof String id) || id.isBlank()) {
                    result.put("status", PlaybookActionStatus.FAILED.wireValue());
                    result.put("mode", "EXECUTED");
                    result.put("verified", false);
                    result.put("errorCode", "MISSING_DISPOSITION_RECEIPT");
                    result.put("error", "incident-web returned 2xx without a caseId");
                } else {
                    result.put("caseId", id);
                    result.put("verified", true);
                    result.put("verification", "http-2xx-and-case-receipt");
                }
            }
            return result;
        }
    }

    private static final class SimulatedHandler implements PlaybookActionHandler {
        private final PlaybookActionType type;

        private SimulatedHandler(PlaybookActionType type) {
            this.type = type;
        }

        @Override
        public PlaybookActionType type() {
            return type;
        }

        @Override
        public Map<String, Object> handle(PlaybookActionContext context) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (!context.simulationAllowed()) {
                result.put("status", PlaybookActionStatus.FAILED.wireValue());
                result.put("mode", "NOT_EXECUTED");
                result.put("errorCode", "SIMULATION_DISABLED");
                result.put("error", "simulation action is disabled; no connector was invoked");
                return result;
            }
            result.put("status", PlaybookActionStatus.SIMULATED.wireValue());
            result.put("mode", "SIMULATED");
            result.put("verified", false);
            result.put("reason", "no real connector is registered; action was simulated");
            return result;
        }
    }

    private static Map<String, Object> verifiedCall(String target, ServiceCall call,
                                                     boolean requireBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (call == null) {
            result.put("status", PlaybookActionStatus.FAILED.wireValue());
            result.put("mode", "NOT_EXECUTED");
            result.put("target", target);
            result.put("verified", false);
            result.put("errorCode", "SERVICE_NO_RESULT");
            result.put("error", "service returned no result");
            return result;
        }
        boolean ok = call.ok() && (!requireBody || (call.body() != null && !call.body().isBlank()));
        result.put("status", ok
                ? PlaybookActionStatus.SUCCESS.wireValue()
                : PlaybookActionStatus.FAILED.wireValue());
        result.put("mode", call.ok() ? "EXECUTED" : "NOT_EXECUTED");
        result.put("verified", ok);
        result.put("httpStatus", call.status());
        result.put("target", target);
        result.put("costMs", call.durationMs());
        if (ok) {
            result.put("verification", "http-2xx-ack");
        } else {
            result.put("error", call.failureReason());
        }
        return result;
    }

    private static Map<String, Object> jsonObject(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(body, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
