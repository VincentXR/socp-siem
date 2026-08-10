package com.socp.platform.client;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 服务地址解析器：把 {@link SocpService} 变成可请求的 URL。
 *
 * <p>解析优先级（第一个非空者生效）：
 * <ol>
 *   <li>配置项 {@code socp.<service>.url}（yml / 启动参数）；</li>
 *   <li>环境变量 {@code SOCP_<SERVICE>_URL}（Spring relaxed binding 自动映射到上一条，
 *       {@code build/ports.env} 就是靠这一层生效的）；</li>
 *   <li>{@code http://localhost:<默认端口>}。</li>
 * </ol>
 *
 * <p>换句话说：容器化部署只要 {@code SOCP_ALERT_URL=http://alert-web:18080} 即可，
 * 不用改任何一行 Java、不用改 yml。
 */
@Component
public class ServiceEndpoints {

    private final Environment env;

    public ServiceEndpoints(Environment env) {
        this.env = env;
    }

    /** 服务根地址，不含 context-path，例 {@code http://localhost:18080}。 */
    public String baseUrl(SocpService svc) {
        String v = env.getProperty(svc.urlProperty());
        if (v == null || v.isBlank()) {
            return svc.defaultBaseUrl();
        }
        return stripTrailingSlash(v.trim());
    }

    /**
     * 拼出完整请求地址：{@code baseUrl + context-path + apiPath}。
     *
     * <p>context-path 由 {@link SocpService#contextPath()} 自动补齐，调用方只写业务路径
     * （例 {@code "/api/alarms"}），不用再手写 {@code "/alert-web/api/alarms"}。
     * 若 apiPath 已经带了 context 前缀（历史调用），不会重复拼接。
     */
    public String url(SocpService svc, String apiPath) {
        String path = apiPath == null ? "" : apiPath.trim();
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        String ctx = svc.contextPath();
        if (!ctx.isEmpty() && !path.equals(ctx) && !path.startsWith(ctx + "/")) {
            path = ctx + path;
        }
        return baseUrl(svc) + path;
    }

    /** 网关地址：服务间换 token、前端代理、验证脚本登录都用它。 */
    public String gatewayUrl() {
        return baseUrl(SocpService.GATEWAY);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
