package com.socp.threat.web.service;

import com.socp.platform.client.http.PinnedHttpTransport;
import com.socp.platform.client.http.ExternalEndpointPolicy;
import com.socp.platform.client.http.PinnedEndpoint;
import com.socp.platform.client.config.SocpClientProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minimal TAXII 2.1 collection client with bounded pagination and HTTPS policy. */
public final class TaxiiClient {

    private final PinnedHttpTransport http;
    private final Duration timeout;
    private final boolean allowHttp;
    private final ExternalEndpointPolicy endpointPolicy;

    public TaxiiClient(Duration timeout, boolean allowHttp, ExternalEndpointPolicy endpointPolicy) {
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.allowHttp = allowHttp;
        this.endpointPolicy = java.util.Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.http = new PinnedHttpTransport();
    }

    public List<String> fetchCollection(URI collection, String authorization) {
        if (collection == null || collection.getHost() == null) throw invalid("TAXII collection URL is invalid");
        if (!allowHttp && !"https".equalsIgnoreCase(collection.getScheme())) {
            throw invalid("TAXII collection must use HTTPS");
        }
        // Every page connects using the original validated resolution and TLS hostname.
        try (PinnedEndpoint pinned = endpointPolicy.validatePinned(collection.toString())) {
            if (pinned.isRejected()) throw invalid("TAXII endpoint rejected: " + pinned.rejectionReason());
            List<String> documents = new ArrayList<>();
            URI next = collection;
            for (int page = 0; next != null && page < 100; page++) {
                validateNext(next, collection);
                java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                headers.put("Accept", "application/taxii+json;version=2.1");
                if (authorization != null && !authorization.isBlank()) headers.put("Authorization", authorization);
                try {
                    int deadline = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
                    var response = http.send("GET", next, null, null, headers, pinned,
                            deadline, deadline, SocpClientProperties.DEFAULT_RESPONSE_BODY_LIMIT_BYTES);
                    if (response.status() / 100 != 2) throw invalid("TAXII HTTP " + response.status());
                    documents.add(response.body());
                    URI link = nextLink(response.body());
                    next = link == null ? null : next.resolve(link);
                } catch (IllegalArgumentException invalidResponse) {
                    // Preserve sanitized protocol/validation errors for checkpoint diagnostics.
                    throw invalidResponse;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("TAXII collection request failed", ex);
                } catch (Exception ex) {
                    throw new IllegalStateException("TAXII collection request failed", ex);
                }
            }
            if (next != null) throw invalid("TAXII pagination exceeded 100 pages");
            return List.copyOf(documents);
        }
    }

    private static URI nextLink(String body) {
        if (body == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode next = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(body).path("next");
            if (next.isTextual() && !next.asText().isBlank()) return URI.create(next.asText());
            return null;
        } catch (Exception ex) {
            throw invalid("invalid TAXII response JSON");
        }
    }

    private void validateNext(URI candidate, URI origin) {
        String rejection = endpointPolicy.validate(candidate.toString());
        if (rejection != null) throw invalid("TAXII endpoint rejected: " + rejection);
        String scheme = candidate.getScheme();
        if (!("https".equalsIgnoreCase(scheme)
                || (allowHttp && "http".equalsIgnoreCase(scheme)))) {
            throw invalid("TAXII pagination link must use HTTPS");
        }
        if (candidate.getHost() == null
                || !candidate.getHost().equalsIgnoreCase(origin.getHost())
                || effectivePort(candidate) != effectivePort(origin)) {
            throw invalid("TAXII pagination link must remain on the configured host");
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
