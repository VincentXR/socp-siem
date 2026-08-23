package com.socp.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产模式启动守卫（2026-08-12，P1）。
 *
 * <p>三档 profile 的「prod」侧：激活 {@code spring.profiles.active=prod} 时执行严格启动校验，
 * 任一违规直接 {@link IllegalStateException} 中止启动（fail-fast），绝不带着隐患跑生产：
 * <ul>
 *   <li>禁止 H2 文件库（spring.datasource.url 含 jdbc:h2）</li>
 *   <li>禁止默认演示 JWT secret（run-all.sh 注入的 demo 值）</li>
 *   <li>禁止 dev-bypass=true（鉴权不得绕过）</li>
 *   <li>禁止默认采集凭据（dev-vector-token）</li>
 *   <li>禁止关闭 Temporal（SOAR 不得回退进程内执行器）</li>
 * </ul>
 *
 * <p>local（默认）：H2/内存回退/默认凭据全允许，开发便利；
 * integration：run-all + compose 全中间件；prod：本守卫 + 环境变量注入真实组件。
 */
@Configuration
@Profile("prod")
public class ProdGuard {

    private static final Logger log = LoggerFactory.getLogger(ProdGuard.class);

    private static final String DEMO_JWT_SECRET = "socp-demo-jwt-secret-0123456789abcdef0123456789abcdef";
    private static final String DEMO_INGEST_TOKEN = "dev-vector-token";
    private static final String DEMO_SERVICE_SECRET = "socp-demo-service-secret-change-me";

    public ProdGuard(Environment env) {
        List<String> violations = new ArrayList<>();

        String url = env.getProperty("spring.datasource.url", "");
        if (url.contains("jdbc:h2")) {
            violations.add("spring.datasource.url 指向 H2（生产禁止 H2，请配置 SOCP_PG_* 指向 PostgreSQL）");
        }

        String secret = env.getProperty("socp.security.jwt-secret", "");
        if (secret.isBlank()) {
            violations.add("socp.security.jwt-secret 未配置");
        } else if (DEMO_JWT_SECRET.equals(secret)) {
            violations.add("socp.security.jwt-secret 使用了默认演示密钥（run-all.sh 的 demo 值）");
        }

        if ("true".equalsIgnoreCase(env.getProperty("socp.security.dev-bypass", "false"))) {
            violations.add("socp.security.dev-bypass=true（生产禁止绕过 JWT 验签）");
        }

        String ingest = env.getProperty("socp.security.ingest-token", "");
        if (DEMO_INGEST_TOKEN.equals(ingest)) {
            violations.add("socp.security.ingest-token 使用了默认演示值 dev-vector-token");
        }

        String serviceSecret = env.getProperty("socp.security.service-secret", "");
        if (serviceSecret.isBlank()) {
            violations.add("socp.security.service-secret is not configured");
        } else if (DEMO_SERVICE_SECRET.equals(serviceSecret)) {
            violations.add("socp.security.service-secret uses the development default");
        }

        if (!"true".equalsIgnoreCase(env.getProperty("socp.temporal.enabled", "true"))) {
            violations.add("socp.temporal.enabled=false（生产禁止 SOAR 回退进程内执行器）");
        }

        if ("memory".equalsIgnoreCase(env.getProperty("socp.ratelimit.backend", "memory"))) {
            violations.add("socp.ratelimit.backend=memory (production requires the shared Redis backend)");
        }

        if (!"kafka".equalsIgnoreCase(env.getProperty("socp.audit.sink", "memory"))) {
            violations.add("socp.audit.sink must be kafka in production");
        }

        if (!"true".equalsIgnoreCase(env.getProperty("socp.audit.fail-closed", "false"))) {
            violations.add("socp.audit.fail-closed must be true in production");
        }

        if ("api-gateway".equals(env.getProperty("spring.application.name"))
                && !"true".equalsIgnoreCase(env.getProperty("socp.auth.cookie-secure", "false"))) {
            violations.add("socp.auth.cookie-secure=false (production session cookies require HTTPS)");
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("【prod 启动校验失败】" + String.join("；", violations));
        }
        log.info("ProdGuard 通过：prod 模式启动校验无违规项");
    }
}
