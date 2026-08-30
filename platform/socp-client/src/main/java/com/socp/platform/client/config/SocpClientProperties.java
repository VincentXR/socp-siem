package com.socp.platform.client.config;


import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务间调用的统一策略开关（{@code socp.client.*}）。全部有默认值，不配也能跑。
 */
@Component
@ConfigurationProperties(prefix = "socp.client")
public class SocpClientProperties {

    /** 建连超时（毫秒）。 */
    private int connectTimeoutMs = 2000;

    /** 单次请求读超时（毫秒）；各 client 可按调用场景覆盖。 */
    private int requestTimeoutMs = 3000;

    /**
     * 最大尝试次数（含首次）。默认 1 = 不重试。
     *
     * <p>为什么默认不重试：告警扇出（notify / incident / soar）都是**非幂等**写操作，
     * 盲目重试会造成重复通知、重复建案。真要开，请先确认下游做了幂等（例如按 alarmId 去重），
     * 再设 {@code socp.client.max-attempts=2}。
     */
    private int maxAttempts = 1;

    /** 重试间隔（毫秒），仅在 maxAttempts > 1 时生效。 */
    private int retryBackoffMs = 200;

    /** 响应体日志截断长度，避免一条 WARN 刷屏。 */
    private int bodyLogLimit = 300;

    /** 服务间调用换 token 用的账号（网关 /auth/login）。 */
    private String username = "demo";

    /** 服务间调用换 token 用的口令。 */
    private String password = "demo123";

    /** token 缓存时长（毫秒），默认 25 分钟（网关签发 30 分钟）。 */
    private long tokenTtlMs = 25 * 60 * 1000L;

    private List<String> externalAllowedHosts = new ArrayList<>();
    private boolean externalHttpsOnly = true;
    private boolean externalAllowPrivateNetworks;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public int getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(int retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public int getBodyLogLimit() {
        return bodyLogLimit;
    }

    public void setBodyLogLimit(int bodyLogLimit) {
        this.bodyLogLimit = bodyLogLimit;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public long getTokenTtlMs() {
        return tokenTtlMs;
    }

    public void setTokenTtlMs(long tokenTtlMs) {
        this.tokenTtlMs = tokenTtlMs;
    }

    public List<String> getExternalAllowedHosts() {
        return List.copyOf(externalAllowedHosts);
    }

    public void setExternalAllowedHosts(List<String> externalAllowedHosts) {
        this.externalAllowedHosts = externalAllowedHosts == null ? new ArrayList<>()
                : new ArrayList<>(externalAllowedHosts);
    }

    public boolean isExternalHttpsOnly() {
        return externalHttpsOnly;
    }

    public void setExternalHttpsOnly(boolean externalHttpsOnly) {
        this.externalHttpsOnly = externalHttpsOnly;
    }

    public boolean isExternalAllowPrivateNetworks() {
        return externalAllowPrivateNetworks;
    }

    public void setExternalAllowPrivateNetworks(boolean externalAllowPrivateNetworks) {
        this.externalAllowPrivateNetworks = externalAllowPrivateNetworks;
    }
}
