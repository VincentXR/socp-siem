package com.socp.platform.client;

import org.springframework.stereotype.Component;

/** 威胁情报服务（threat-web）客户端：告警富化时批量匹配 IOC。 */
@Component
public class ThreatClient {

    private final SocpHttpClient http;

    public ThreatClient(SocpHttpClient http) {
        this.http = http;
    }

    /**
     * 批量匹配 IOC（{@code POST /threat-web/api/v1/iocs/match}）。
     *
     * @param valuesJsonArray 待匹配值的 JSON 数组，例 {@code ["1.2.3.4","evil.com"]}
     */
    public ServiceCall matchIocs(String valuesJsonArray) {
        return http.postJson(SocpService.THREAT, "/api/v1/iocs/match", valuesJsonArray);
    }
}
