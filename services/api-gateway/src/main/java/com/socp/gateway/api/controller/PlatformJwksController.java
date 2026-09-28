package com.socp.gateway.api.controller;

import com.socp.gateway.security.PlatformTokenIssuer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Publishes only the public platform signing keys used by downstream services. */
@RestController
public class PlatformJwksController {

    private final PlatformTokenIssuer tokenIssuer;

    public PlatformJwksController(PlatformTokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    @GetMapping("/.well-known/socp-jwks.json")
    public Map<String, Object> jwks() {
        return tokenIssuer.publicJwkSet();
    }
}
