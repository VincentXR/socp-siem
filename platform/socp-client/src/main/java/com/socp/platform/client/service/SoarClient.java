package com.socp.platform.client.service;




import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 编排服务（soar-web）客户端：告警触发剧本评估。 */
@Component
public class SoarClient {

    private final SocpHttpClient http;

    public SoarClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 评估并执行命中的剧本（{@code POST /soar-web/api/v1/playbooks/evaluate}）。 */
    public ServiceCall evaluate(String alarmJson) {
        return http.postJson(SocpService.SOAR, "/api/v1/playbooks/evaluate", alarmJson);
    }
}
