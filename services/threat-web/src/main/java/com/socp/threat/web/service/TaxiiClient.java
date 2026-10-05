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
            java.util.Set<URI> visited = new java.util.HashSet<>();
            URI next = collection;
            for (int page = 0; next != null && page < 100; page++) {
                if (!visited.add(next)) throw invalid("TAXII pagination did not advance");
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
                    next = nextPage(next, response);
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

    private static URI nextPage(URI current, PinnedHttpTransport.Response response) {
        com.fasterxml.jackson.databind.JsonNode envelope;
        try {
            envelope = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
        } catch (Exception ex) {
            throw invalid("invalid TAXII response JSON");
        }
        if (envelope == null || !envelope.isObject()) throw invalid("invalid TAXII response JSON");
        var more = envelope.path("more");
        if (!more.isMissingNode() && !more.isBoolean()) throw invalid("TAXII more must be a boolean");
        if (!more.asBoolean(false)) return null;
        var token = envelope.path("next");
        if (!token.isMissingNode() && !token.isNull() && !token.isTextual())
            throw invalid("TAXII next must be a string");
        if (token.isTextual() && !token.asText().isEmpty()) {
            if (token.asText().length() > 4096) throw invalid("TAXII next token exceeds limit");
            return pageUri(current, "next", token.asText());
        }
        // TAXII 2.1 section 3.5 permits timestamp pagination when no next token is supplied.
        String last = response.header("X-TAXII-Date-Added-Last");
        try {
            java.time.Instant watermark = java.time.Instant.parse(last);
            String previous = queryValue(current, "added_after");
            if (previous != null && !watermark.isAfter(java.time.Instant.parse(previous)))
                throw invalid("TAXII pagination timestamp did not advance");
        } catch (java.time.format.DateTimeParseException | NullPointerException invalidHeader) {
            throw invalid("TAXII more requires next or a valid X-TAXII-Date-Added-Last");
        }
        return pageUri(current, "added_after", last);
    }

    private static URI pageUri(URI original, String parameter, String value) {
        List<String> query = new ArrayList<>();
        if (original.getRawQuery() != null) for (String part : original.getRawQuery().split("&")) {
            String name = java.net.URLDecoder.decode(part.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
            if (!name.equals("next") && !(parameter.equals("added_after") && name.equals("added_after")))
                query.add(part);
        }
        query.add(parameter + "=" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        // The token is query data, never a URL. Original path, host and filters remain fixed.
        return URI.create(original.getScheme() + "://" + original.getRawAuthority() + original.getRawPath()
                + "?" + String.join("&", query));
    }

    private static String queryValue(URI uri, String key) {
        if (uri.getRawQuery() == null) return null;
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (java.net.URLDecoder.decode(pair[0], java.nio.charset.StandardCharsets.UTF_8).equals(key))
                return pair.length == 1 ? "" : java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
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
