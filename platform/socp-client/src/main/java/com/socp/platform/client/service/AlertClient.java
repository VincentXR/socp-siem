package com.socp.platform.client.service;




import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 告警服务（alert-web）客户端：检测侧转发告警、报表侧拉取统计。 */
@Component
public class AlertClient {

    private final SocpHttpClient http;

    public AlertClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 转发一条告警到 alert-web 落库（{@code POST /alert-web/api/alarms}）。 */
    public ServiceCall forwardAlarm(String alarmJson) {
        return http.postJson(SocpService.ALERT, "/api/alarms", alarmJson);
    }

    /** 拉取告警聚合统计（{@code GET /alert-web/api/alarms/stats}），报表回退路径使用。 */
    public ServiceCall stats() {
        return http.get(SocpService.ALERT, "/api/alarms/stats");
    }

    /** Fetch an explicit statistics window, for example {@code today} or {@code 7d}. */
    public ServiceCall stats(String window) {
        if (window == null || window.isBlank()) return stats();
        return http.get(SocpService.ALERT, "/api/alarms/stats?window=" + window.trim());
    }

    /** Fetch one tenant-scoped alarm fact for investigation tooling. */
    public ServiceCall getAlarm(String alarmId) {
        return http.get(SocpService.ALERT, "/api/alarms/" + encode(alarmId));
    }

    /** Fetch the immutable source-event snapshot captured with an alarm. */
    public ServiceCall evidence(String alarmId) {
        return http.get(SocpService.ALERT, "/api/alarms/" + encode(alarmId) + "/evidence");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
