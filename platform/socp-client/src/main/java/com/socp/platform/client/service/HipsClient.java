package com.socp.platform.client.service;




import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 主机防护服务（hips-web）客户端：端点侧上报进程/文件/网络事件。 */
@Component
public class HipsClient {

    private final SocpHttpClient http;

    public HipsClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 上报一条端点事件（{@code POST /hips-web/api/v1/endpoints/events}）。 */
    public ServiceCall reportEvent(String eventJson) {
        return http.postJson(SocpService.HIPS, "/api/v1/endpoints/events", eventJson);
    }
}
