package com.socp.gateway;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keycloak OIDC 登录代理（2026-08-12，PKCE 授权码流程）。
 *
 * <p>Keycloak 只作身份源：回调里用 PKCE code 换到 id_token 后，解析 role/tenant claim，
 * <b>由网关统一签发 HS256 session token</b>（与 /auth/login 同一 secret），前端继续用
 * localStorage 存它调业务服务——业务服务保持 HMAC 验签零改动，demo 登录完全不受影响。
 *
 * <p>流程：GET /auth/oidc/login（302 → Keycloak 授权端点，带 state+PKCE challenge）
 * → Keycloak 登录页 → 302 回 GET /auth/oidc/callback?code&state
 * → 换 token → 签统一 token → 302 到前端 ?socp_oidc_token=xxx。
 */
@RestController
@RequestMapping("/auth/oidc")
public class OidcAuthController {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthController.class);

    /** state → (PKCE verifier, 过期时间)；内存态，单实例可接受，网关重启丢 state（回调会重登）。 */
    private static final long STATE_TTL_MS = 30 * 60 * 1000L;
    private final Map<String, StateEntry> states = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${socp.oidc.issuer-uri:}")
    private String issuerUri;

    @Value("${socp.oidc.client-id:socp-spa}")
    private String clientId;

    @Value("${socp.oidc.redirect-uri:}")
    private String redirectUri;

    @Value("${socp.oidc.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    private final AuthController authController;

    public OidcAuthController(AuthController authController) {
        this.authController = authController;
    }

    private record StateEntry(String verifier, long expiresAt) {
    }

    @GetMapping("/login")
    public ResponseEntity<?> login() {
        if (issuerUri == null || issuerUri.isBlank()) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "socp.oidc.issuer-uri 未配置");
        }
        String verifier = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String state = UUID.randomUUID().toString();
        states.put(state, new StateEntry(verifier, System.currentTimeMillis() + STATE_TTL_MS));
        String authUrl = issuerUri + "/protocol/openid-connect/auth"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&scope=openid"
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(codeChallenge(verifier))
                + "&code_challenge_method=S256";
        log.info("OIDC 登录跳转 issuer={} state={}", issuerUri, state);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authUrl)).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<?> callback(@RequestParam("code") String code,
                                      @RequestParam("state") String state) {
        StateEntry entry = states.remove(state);
        if (entry == null || entry.expiresAt() < System.currentTimeMillis()) {
            return error(HttpStatus.UNAUTHORIZED, "OIDC state 无效或已过期，请重新登录");
        }
        try {
            Map<String, Object> idClaims = exchangeCode(code, entry.verifier());
            String subject = first(idClaims, "preferred_username", "sub");
            String role = first(idClaims, "role", null);
            String tenant = first(idClaims, "tenant", null);
            String token = authController.sign(subject, role == null ? "analyst" : role, tenant);
            String redirect = frontendUrl + "?socp_oidc_token=" + token;
            log.info("OIDC 登录成功 subject={} role={} tenant={} → {}", subject, role, tenant, frontendUrl);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirect)).build();
        } catch (Exception e) {
            log.warn("OIDC 回调处理失败: {}", e.getMessage());
            return error(HttpStatus.UNAUTHORIZED, "OIDC 登录失败: " + e.getMessage());
        }
    }

    /** 用授权码 + PKCE verifier 换 token，解析 id_token 的 claims（可信 token 端点交换）。 */
    private Map<String, Object> exchangeCode(String code, String verifier) throws Exception {
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(clientId)
                + "&code_verifier=" + enc(verifier);
        HttpRequest req = HttpRequest.newBuilder(URI.create(issuerUri + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("token 端点返回 " + resp.statusCode() + ": " + resp.body());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(resp.body(), Map.class);
        String idToken = String.valueOf(json.get("id_token"));
        SignedJWT jwt = SignedJWT.parse(idToken);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub", claims.getSubject());
        out.put("preferred_username", claims.getStringClaim("preferred_username"));
        out.put("role", claims.getStringClaim("role"));
        out.put("tenant", claims.getStringClaim("tenant"));
        return out;
    }

    private static String first(Map<String, Object> m, String k1, String fallback) {
        Object v = m.get(k1);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("PKCE code_challenge 生成失败", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
