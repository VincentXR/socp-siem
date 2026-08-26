package com.socp.platform.client.service;




import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 案件服务（incident-web）客户端：由告警自动建案 / 归并。 */
@Component
public class IncidentClient {

    private final SocpHttpClient http;

    public IncidentClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 由告警建案（{@code POST /incident-web/api/v1/incidents/from-alarm}），同实体会归并到已有案件。 */
    public ServiceCall createFromAlarm(String alarmJson) {
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/from-alarm", alarmJson);
    }

    /** List the current tenant's cases for investigation correlation. */
    public ServiceCall list() {
        return http.get(SocpService.INCIDENT, "/api/v1/incidents");
    }

    /** Append an analyst-approved investigation summary to a case timeline. */
    public ServiceCall addNote(String caseId, String author, String content) {
        return addNote(caseId, author, content, null);
    }

    /** Append a note with a stable key so a remote success can be safely replayed. */
    public ServiceCall addNote(String caseId, String author, String content, String idempotencyKey) {
        String id = encode(caseId);
        String query = "?author=" + encode(author) + "&content=" + encode(content);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            query += "&idempotencyKey=" + encode(idempotencyKey);
        }
        return http.postJson(SocpService.INCIDENT, "/api/v1/incidents/" + id + "/notes" + query, "{}");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
