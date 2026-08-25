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
}
