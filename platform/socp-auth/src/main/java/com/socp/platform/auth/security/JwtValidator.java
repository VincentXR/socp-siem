package com.socp.platform.auth.security;
import com.socp.platform.auth.config.SocpSecurityProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * JWT 校验器：验签 + 过期（exp/nbf）+ 可选 issuer 精确匹配。
 *
 * 两种签名模式二选一（见 {@link SocpSecurityProperties}）：
 *  - 非对称：从 JWKS 端点拉公钥，接受 RS256/RS384/RS512/ES256/ES384/ES512（Keycloak 默认 RS256）；
 *  - 对称：HS256/HS384/HS512，密钥来自 socp.security.jwt-secret。
 *
 * 【零 Web 依赖】本类只依赖 nimbus-jose-jwt 与 slf4j，不碰 jakarta.servlet / spring-web，
 * 因此 Servlet 服务（AuthInterceptor）与 WebFlux 网关（GatewayFilter）可以共用同一份实现。
 *
 * 【dev-bypass】未配置 JWKS/secret 时 {@link #isDevBypass()} 为 true，调用方只校验 Bearer 非空，
 * 保证无 Docker 环境下 demo-token 演示流继续可用；启动时打 WARN 提示这不是生产姿势。
 */
public class JwtValidator {

    private static final Logger log = LoggerFactory.getLogger(JwtValidator.class);

    /** JWKS 缓存 5 分钟，避免每个请求都打 Keycloak；nimbus 内部还有 rate-limit 兜底 */
    private static final long JWKS_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long JWKS_CACHE_REFRESH_TIMEOUT_MS = 15 * 1000L;

    private static final Set<JWSAlgorithm> ASYMMETRIC_ALGS = new LinkedHashSet<>(Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512));

    private static final Set<JWSAlgorithm> SYMMETRIC_ALGS = new LinkedHashSet<>(Set.of(
            JWSAlgorithm.HS256, JWSAlgorithm.HS384, JWSAlgorithm.HS512));

    private final SocpSecurityProperties props;
    private final boolean devBypass;
    private final ConfigurableJWTProcessor<SecurityContext> processor;

    public JwtValidator(SocpSecurityProperties props) {
        this.props = props;
        this.devBypass = props.resolveDevBypass();
        if (devBypass) {
            this.processor = null;
            log.warn("【安全告警】socp-auth 运行在 dev-bypass 模式：任意非空 Bearer 令牌均放行，签名与过期均不校验。"
                    + " 生产环境必须配置 socp.security.issuer-uri（Keycloak）或 socp.security.jwt-secret，"
                    + " 并显式设置 socp.security.dev-bypass=false。");
        } else {
            this.processor = buildProcessor(props);
            log.info("socp-auth JWT 校验已启用，模式={}", props.hasJwks() ? "JWKS:" + props.resolveJwkSetUri() : "HMAC");
        }
    }

    /** 开发回退模式：调用方只需检查 Bearer 非空 */
    public boolean isDevBypass() {
        return devBypass;
    }

    private static final java.util.Set<String> REVOKED_JTIS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Actively revoke a JWT by its JTI (e.g. upon logout or security incident). */
    public static void revoke(String jti) {
        if (jti != null && !jti.isBlank()) {
            REVOKED_JTIS.add(jti.trim());
        }
    }

    /** Clear revoked JTI cache (e.g. for testing). */
    public static void clearRevoked() {
        REVOKED_JTIS.clear();
    }

    /**
     * 校验令牌并返回 claims。
     *
     * @throws JwtValidationException 签名无效 / 已过期 / issuer 不匹配 / 格式非法 / 已被吊销
     * @throws IllegalStateException  dev-bypass 模式下调用（调用方应先判 {@link #isDevBypass()}）
     */
    public JWTClaimsSet validate(String token) {
        if (devBypass) {
            throw new IllegalStateException("dev-bypass 模式下不应调用 validate()，请先判断 isDevBypass()");
        }
        if (token == null || token.isBlank()) {
            throw new JwtValidationException("空令牌");
        }
        try {
            JWTClaimsSet claims = processor.process(token.trim(), null);
            String jti = claims.getJWTID();
            if (jti != null && REVOKED_JTIS.contains(jti)) {
                throw new JwtValidationException("令牌已被吊销 (revoked)");
            }
            return claims;
        } catch (JwtValidationException e) {
            throw e;
        } catch (Exception e) {
            // 不把底层异常细节回给客户端调用方之外的地方；message 已足够定位（签名/过期/issuer）
            throw new JwtValidationException("JWT 校验失败: " + e.getMessage(), e);
        }
    }

    /** Extract an explicit tenant claim; client identity is never treated as a tenant. */
    public String extractTenant(JWTClaimsSet claims) {
        if (claims == null) {
            return null;
        }
        for (String name : new String[]{props.getTenantClaim(), "tenant_id", "tid"}) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Object v = claims.getClaim(name);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildProcessor(SocpSecurityProperties props) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

        if (props.hasJwks()) {
            String uri = props.resolveJwkSetUri();
            URL url;
            try {
                // new URL(String) 自 JDK 20 起废弃，走 URI 转换
                url = URI.create(uri).toURL();
            } catch (Exception e) {
                throw new IllegalStateException("socp.security 的 JWKS 地址非法: " + uri, e);
            }
            JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.<SecurityContext>create(url)
                    .cache(JWKS_CACHE_TTL_MS, JWKS_CACHE_REFRESH_TIMEOUT_MS)
                    .build();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ASYMMETRIC_ALGS, jwkSource));
        } else if (props.hasSecret()) {
            byte[] secret = props.getJwtSecret().getBytes(StandardCharsets.UTF_8);
            // nimbus 对 HS256 强制要求 >=256 位密钥，短密钥会在首次校验时才炸，提前拦在启动期
            if (secret.length < 32) {
                throw new IllegalStateException("socp.security.jwt-secret 至少需要 32 字节（HS256 要求 256 位密钥）");
            }
            processor.setJWSKeySelector(
                    new JWSVerificationKeySelector<>(SYMMETRIC_ALGS, new ImmutableSecret<>(secret)));
        } else {
            // dev-bypass=false 但没给任何密钥来源：直接失败，绝不静默降级成"人人可过"
            throw new IllegalStateException("socp.security.dev-bypass=false 时必须配置 issuer-uri/jwk-set-uri 或 jwt-secret");
        }

        JWTClaimsSet exactMatch = null;
        if (props.isValidateIssuer() && props.getIssuerUri() != null && !props.getIssuerUri().isBlank()) {
            exactMatch = new JWTClaimsSet.Builder().issuer(props.getIssuerUri().trim()).build();
        }
        // DefaultJWTClaimsVerifier 自带 exp/nbf 校验；requiredClaims 强制令牌必须带 exp，杜绝永不过期的令牌
        Set<String> acceptedAudiences = props.resolveAudiences();
        if (acceptedAudiences.isEmpty()) {
            throw new IllegalStateException("socp.security.audience is required when JWT verification is enabled");
        }
        DefaultJWTClaimsVerifier<SecurityContext> verifier =
                new DefaultJWTClaimsVerifier<>(
                        acceptedAudiences,
                        exactMatch,
                        Set.of("exp"),
                        null);
        verifier.setMaxClockSkew(props.getClockSkewSeconds());
        processor.setJWTClaimsSetVerifier(verifier);

        return processor;
    }
}
