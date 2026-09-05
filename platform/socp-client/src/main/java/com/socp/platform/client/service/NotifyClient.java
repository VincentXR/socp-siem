package com.socp.platform.client.service;


import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 通知服务（notify-web）客户端：告警多渠道分发。 */
@Component
public class NotifyClient {

    private final SocpHttpClient http;

    public NotifyClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 按已启用渠道分发告警通知（{@code POST /notify-web/api/v1/notify/alert}）。 */
    public ServiceCall notifyAlert(String alarmJson) {
        return http.postJson(SocpService.NOTIFY, "/api/v1/notify/alert", alarmJson);
    }

    /** Notification fan-out with a stable SOAR idempotency key. */
    public ServiceCall notifyAlert(String alarmJson, String idempotencyKey) {
        Map<String, String> headers = idempotencyKey == null || idempotencyKey.isBlank()
                ? Map.of() : Map.of("Idempotency-Key", idempotencyKey);
        return http.postJson(SocpService.NOTIFY, "/api/v1/notify/alert", alarmJson, headers);
    }
}
