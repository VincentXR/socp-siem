package com.socp.platform.client;

/**
 * SOCP 内部服务枚举：服务间调用的**唯一**地址来源。
 *
 * <p>在此之前，每个调用方都写着 {@code @Value("${socp.alert.url:http://localhost:18080}")}，
 * 端口默认值散落在 8 个服务的 Java 源码 + yml 里；改端口就得全仓 grep。现在收敛到这一张表：
 *
 * <ol>
 *   <li>运行期优先读配置项 {@link #urlProperty()}（例：{@code socp.alert.url}）；</li>
 *   <li>Spring 的 relaxed binding 会自动把环境变量 {@code SOCP_ALERT_URL} 映射到该配置项，
 *       所以 {@code build/ports.env} 导出的变量天然生效，无需在 yml 里逐个写占位符；</li>
 *   <li>都没有时才回退到 {@code http://localhost:<defaultPort>}。</li>
 * </ol>
 *
 * <p>这里的默认端口与 {@code build/ports.env} 保持一致，由
 * {@code ServiceEndpointsPortsEnvTest} 在构建期强制校验，不会再出现两边偷偷漂移。
 *
 * <p>context-path 约定：api-gateway 挂根路径（空 context），其余服务 context-path == 服务名。
 */
public enum SocpService {

    ALERT("alert-web", 18080),
    SEARCH("search-config", 18081),
    DETECT("detect-web", 18082),
    SOAR("soar-web", 18083),
    REPORT("report-web", 18084),
    ASSET("asset-web", 18085),
    SOC("soc-base", 18086),
    HIPS("hips-web", 18087),
    AI("ai-assistant", 18088),
    DETECT_MODEL("detect-model", 18090),
    ASSET_COLLECT("asset-collect", 18091),
    GATEWAY("api-gateway", 18092),
    HIPS_COLLECT("hips-collect", 18093),
    THREAT("threat-web", 18094),
    ATTACK("attack-web", 18095),
    NOTIFY("notify-web", 18096),
    INCIDENT("incident-web", 18097);

    private final String serviceName;
    private final int defaultPort;
    private final String urlProperty;

    SocpService(String serviceName, int defaultPort) {
        this.serviceName = serviceName;
        this.defaultPort = defaultPort;
        // ALERT -> socp.alert.url / SOCP_ALERT_URL；DETECT_MODEL -> socp.detect-model.url
        this.urlProperty = "socp." + name().toLowerCase().replace('_', '-') + ".url";
    }

    /** 服务名，同时也是 context-path（网关除外）。 */
    public String serviceName() {
        return serviceName;
    }

    /** 与 build/ports.env 对齐的默认端口。 */
    public int defaultPort() {
        return defaultPort;
    }

    /** 配置项名，例 {@code socp.alert.url}；环境变量形式为 {@code SOCP_ALERT_URL}。 */
    public String urlProperty() {
        return urlProperty;
    }

    /** context-path：网关为空串，其余等于服务名。 */
    public String contextPath() {
        return this == GATEWAY ? "" : "/" + serviceName;
    }

    /** 兜底地址（配置与环境变量都缺失时使用）。 */
    public String defaultBaseUrl() {
        return "http://localhost:" + defaultPort;
    }
}
