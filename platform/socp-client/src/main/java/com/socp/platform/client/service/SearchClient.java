package com.socp.platform.client.service;

import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** Tenant-scoped search client used by the alert investigation agent. */
@Component
public class SearchClient {

    private final SocpHttpClient http;

    public SearchClient(SocpHttpClient http) {
        this.http = http;
    }

    public ServiceCall search(String expression) {
        String query = java.net.URLEncoder.encode(expression == null ? "" : expression,
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return http.get(SocpService.SEARCH, "/api/v1/search?q=" + query);
    }
}
