package com.socp.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录端点：校验内置账号后签发 HS256 JWT（30 分钟有效，含 tenant claim）。
 * 签发 secret 独立配置（socp.auth.login-secret），不影响 socp-auth 的 dev-bypass 判定：
 *  - 演示模式：dev-bypass=true，网关/服务只校验 Bearer 非空，前端登录拿到的真 JWT 与 demo-token 均可用；
 *  - 生产模式：把同一 secret 配到 socp.security.jwt-secret 并设 dev-bypass=false，全链路强制验签。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Map<String, String> USERS = Map.of(
            "admin", "admin123",
            "demo", "demo123",
            "viewer", "viewer123");
    private static final Map<String, String> ROLES = Map.of(
            "admin", "admin",
            "demo", "analyst",
            "viewer", "viewer");
    private static final long EXPIRES_SECONDS = 1800;

    @Value("${socp.auth.login-secret:socp-demo-jwt-secret-0123456789abcdef0123456789abcdef}")
    private String secret;

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> login(@RequestBody Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (username == null || password == null || !password.equals(USERS.get(username))) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", 401);
            err.put("message", "账号或密码错误");
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err));
        }
        String token = sign(username, ROLES.getOrDefault(username, "analyst"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("username", username);
        out.put("role", ROLES.getOrDefault(username, "analyst"));
        out.put("tenant", "default");
        out.put("expiresIn", EXPIRES_SECONDS);
        return Mono.just(ResponseEntity.ok(out));
    }

    private String sign(String username, String role) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .issuer("socp-gateway")
                    .claim("tenant", "default")
                    .claim("role", role)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(EXPIRES_SECONDS)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }
}
