package com.socp.platform.client.service;


import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 资产服务（asset-web）客户端：采集器上报发现的资产。 */
@Component
public class AssetClient {

    private final SocpHttpClient http;

    public AssetClient(SocpHttpClient http) {
        this.http = http;
    }

    /** 上报一台资产（{@code POST /asset-web/api/v1/assets/collect}）。 */
    public ServiceCall collect(String assetJson) {
        return http.postJson(SocpService.ASSET, "/api/v1/assets/collect", assetJson);
    }
}
