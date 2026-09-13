package com.socp.gateway.security;

import java.net.http.HttpResponse;

/** Bounded response handlers for the gateway's direct OIDC HTTP client. */
public final class BoundedBodyHandlers {

    private BoundedBodyHandlers() {
    }

    public static HttpResponse.BodyHandler<String> ofString(int maxBytes) {
        return new BoundedStringBodyHandler(Math.max(1, maxBytes));
    }
}
