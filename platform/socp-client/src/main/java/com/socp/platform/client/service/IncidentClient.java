package com.socp.platform.client.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 案件服务（incident-web）客户端：由告警自动建案 / 归并。 */
@Component
public class IncidentClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SocpHttpClient http;

    public IncidentClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 由告警建案（{@code POST /incident-web/api/v1/incidents/from-alarm}），同实体会归并到已有案件。 */
    public ServiceCall createFromAlarm(String alarmJson) {
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", alarmJson);
    }

    /** Create/merge a case with a stable request key for SOAR replay safety. */
    public ServiceCall createFromAlarm(String alarmJson, String idempotencyKey) {
        Map<String, String> headers = idempotencyKey == null || idempotencyKey.isBlank()
                ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", alarmJson, headers);
    }

    /** List the current tenant's cases for investigation correlation. */
    public ServiceCall list() {
        return http.get(SocpService.INCIDENT, "/api/v1/incidents");
    }

    public ServiceCall get(String caseId) {
        return http.get(SocpService.INCIDENT, "/api/v1/incidents/" + encode(caseId));
    }

    /** Create an operator-style incident, without pretending it originated from an alert. */
    public ServiceCall create(String incidentJson) {
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents", incidentJson);
    }

    public ServiceCall byAlarm(String alarmId) {
        return http.get(SocpService.INCIDENT, "/api/v1/incidents/by-alarm?alarmId=" + encode(alarmId));
    }

    /** Append an analyst-approved investigation summary to a case timeline. */
    public ServiceCall addNote(String caseId, String author, String content) {
        return addNote(caseId, author, content, null);
    }

    /**
     * Append the JSON mutation DTO with a stable key so a remote success can be
     * safely replayed. Unkeyed calls get one key per invocation, retained across
     * transport retries; callers needing command replay must supply their key.
     */
    public ServiceCall addNote(String caseId, String author, String content, String idempotencyKey) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString() : idempotencyKey;
        String body = JSON.createObjectNode().put("content", content).put("idempotencyKey", key).toString();
        // The server resolves this delegate against the authenticated identity;
        // human callers cannot override the recorded author.
        String query = author == null || author.isBlank() ? "" : "?author=" + encode(author);
        return http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/" + encode(caseId) + "/notes" + query, body);
    }

    public ServiceCall change(String caseId, Map<String, Object> command) {
        try {
            return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/" + encode(caseId) + "/changes",
                    JSON.writeValueAsString(command));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid case command", invalid);
        }
    }

    public ServiceCall assign(String caseId, String assignee, long expectedVersion, String key) {
        String query = "?expectedVersion=" + expectedVersion + "&idempotencyKey=" + encode(key)
                + "&assignee=" + encode(assignee);
        return http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/" + encode(caseId) + "/assignee" + query, "{}");
    }

    /** Legacy unversioned callers receive a validation error; use change instead. */
    @Deprecated
    public ServiceCall setStatus(String caseId, String status, String assignee) {
        String query = "?status=" + encode(status);
        if (assignee != null && !assignee.isBlank()) query += "&assignee=" + encode(assignee);
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/" + encode(caseId) + "/status" + query, "{}");
    }

    /** Legacy unversioned callers receive a validation error; use the versioned overload. */
    @Deprecated
    public ServiceCall assign(String caseId, String assignee) {
        String query = assignee == null || assignee.isBlank() ? ""
                : "?assignee=" + encode(assignee);
        return http.postJson(SocpService.INCIDENT,
                "/api/v1/incidents/" + encode(caseId) + "/assignee" + query, "{}");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
