package com.socp.platform.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 鉴权配置（application.yml 前缀 socp.security）。
 *
 * 三种模式，按优先级判定：
 *  1. jwk-set-uri / issuer-uri 存在 → 非对称签名（RS256/ES256），走 Keycloak JWKS 远程公钥；
 *  2. jwt-secret 存在            → 对称签名（HS256），开发/单机环境用；
 *  3. 两者都缺                    → dev-bypass 自动置 true，只校验 Bearer 非空（保留 demo-token 演示流）。
 *
 * dev-bypass 显式配置时以显式值为准；显式 false 但未配置密钥/JWKS 会在启动期直接失败，
 * 避免“以为开了校验其实没开”的静默降级。
 */
@ConfigurationProperties(prefix = "socp.security")
public class SocpSecurityProperties {

    /** OIDC 签发者，例如 http://localhost:8180/realms/socp；未显式配 jwk-set-uri 时按 Keycloak 约定拼 JWKS 地址 */
    private String issuerUri;

    /** JWKS 公钥端点；配置后优先于 issuer-uri 推导的地址 */
    private String jwkSetUri;

    /** HMAC 对称密钥（HS256），至少 32 字节；仅开发/单机用，生产走 issuer-uri */
    private String jwtSecret;

    /** null = 自动（无 JWKS 且无 secret 时为 true）；显式 true/false 覆盖自动判定 */
    private Boolean devBypass;

    /** 租户 claim 名，校验通过后写入 TenantContext */
    private String tenantClaim = "tenant";

    /** 机机采集凭据（Vector/Agent 静态 token）：Authorization 与之匹配时跳过 JWT 验签直接放行。
     *  仅用于采集端点（ingest/collect），比正式 JWT 简单且不依赖网关签发。默认关闭。 */
    private String ingestToken;

    /** Shared HMAC key for signed tenant delegation between internal services. */
    private String serviceSecret;

    /** Maximum accepted clock skew for signed service requests. */
    private int serviceMaxSkewSeconds = 60;

    /** 允许的时钟偏移（秒），用于 exp/nbf 校验 */
    private int clockSkewSeconds = 60;

    /** 是否校验 iss 与 issuer-uri 完全一致 */
    private boolean validateIssuer = true;

    /**
     * JWT audience 允许值。支持逗号分隔的多个 audience；为空时不启用 audience 校验，
     * 以兼容开发回退模式和已有的内部 HMAC token。
     */
    private String audience;

    public boolean hasJwks() {
        return notBlank(jwkSetUri) || notBlank(issuerUri);
    }

    public boolean hasSecret() {
        return notBlank(jwtSecret);
    }

    /**
     * 返回配置的 audience 集合。配置非空时 JwtValidator 要求令牌至少包含其中一个值。
     */
    public Set<String> resolveAudiences() {
        if (!notBlank(audience)) {
            return Collections.emptySet();
        }
        return Arrays.stream(audience.split(","))
                .map(String::trim)
                .filter(SocpSecurityProperties::notBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** 实际生效的 JWKS 地址：显式 jwk-set-uri 优先，否则按 Keycloak 约定从 issuer-uri 推导 */
    public String resolveJwkSetUri() {
        if (notBlank(jwkSetUri)) {
            return jwkSetUri.trim();
        }
        if (notBlank(issuerUri)) {
            String base = issuerUri.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/protocol/openid-connect/certs";
        }
        return null;
    }

    /** 最终是否走开发回退模式 */
    public boolean resolveDevBypass() {
        if (devBypass != null) {
            return devBypass;
        }
        return !hasJwks() && !hasSecret();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public String getIssuerUri() {
        return issuerUri;
    }

    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Boolean getDevBypass() {
        return devBypass;
    }

    public void setDevBypass(Boolean devBypass) {
        this.devBypass = devBypass;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }

    public String getIngestToken() {
        return ingestToken;
    }

    public void setIngestToken(String ingestToken) {
        this.ingestToken = ingestToken;
    }

    public String getServiceSecret() {
        return serviceSecret;
    }

    public void setServiceSecret(String serviceSecret) {
        this.serviceSecret = serviceSecret;
    }

    public int getServiceMaxSkewSeconds() {
        return serviceMaxSkewSeconds;
    }

    public void setServiceMaxSkewSeconds(int serviceMaxSkewSeconds) {
        this.serviceMaxSkewSeconds = Math.max(1, serviceMaxSkewSeconds);
    }

    public int getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(int clockSkewSeconds) {
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public boolean isValidateIssuer() {
        return validateIssuer;
    }

    public void setValidateIssuer(boolean validateIssuer) {
        this.validateIssuer = validateIssuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }
}
