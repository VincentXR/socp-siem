package com.socp.platform.auth.security;

import java.util.Set;

/** Exact viewer query routes and the session-owner logout operation; CSRF remains independently enforced. */
public final class ReadOnlyRequests {
    private static final Set<String> POST_QUERIES = Set.of(
            "/alert-web/api/alarms/technique-counts",
            "/alert-web/api/v1/alarms/technique-counts",
            "/detect-web/api/v1/rules/lookup");
    private ReadOnlyRequests() { }
    public static boolean allowed(String method, String path) {
        return Set.of("GET", "HEAD", "OPTIONS").contains(method)
                || "POST".equals(method) && (POST_QUERIES.contains(path) || "/auth/logout".equals(path));
    }
}
