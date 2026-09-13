package com.socp.platform.client.http;

import java.net.http.HttpResponse;

/** Shared bounded response handlers for JDK HttpClient integrations. */
public final class BoundedBodyHandlers {

    private BoundedBodyHandlers() {
    }

    /** A string handler that always enforces a finite, positive byte limit. */
    public static HttpResponse.BodyHandler<String> ofString(int maxBytes) {
        return new BoundedStringBodyHandler(Math.max(1, maxBytes));
    }
}
