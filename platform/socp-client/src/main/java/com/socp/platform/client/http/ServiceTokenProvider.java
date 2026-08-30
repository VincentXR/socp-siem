package com.socp.platform.client.http;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.config.SocpClientProperties;
import com.socp.platform.client.config.ServiceEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Exchanges the configured service credential for a short-lived bearer token. */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServiceEndpoints endpoints;
    private final SocpClientProperties properties;
    private final HttpClient http;
    private final String serviceName;
    private final String serviceSecret;
    private final Object lock = new Object();

    private volatile String cachedToken;
    private volatile long expireAt;
    private volatile long lastWarnAt;

    public ServiceTokenProvider(ServiceEndpoints endpoints, SocpClientProperties properties,
                                @Value("${spring.application.name:socp-service}") String serviceName,
                                @Value("${socp.security.service-secret:}") String serviceSecret) {
        this.endpoints = endpoints;
        this.properties = properties;
        this.serviceName = serviceName;
        this.serviceSecret = serviceSecret;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public String token() {
        long now = System.currentTimeMillis();
        String current = cachedToken;
        if (current != null && now < expireAt) return current;
        synchronized (lock) {
            now = System.currentTimeMillis();
            if (cachedToken != null && now < expireAt) return cachedToken;
            String fresh = exchange();
            if (fresh == null) throw new IllegalStateException("service token exchange failed");
            cachedToken = fresh;
            expireAt = now + properties.getTokenTtlMs();
            return fresh;
        }
    }

    public void invalidate() {
        synchronized (lock) {
            cachedToken = null;
            expireAt = 0L;
        }
    }

    private String exchange() {
        String url = endpoints.gatewayUrl() + "/auth/service-token";
        if (serviceSecret == null || serviceSecret.isBlank()) {
            warn("Service token exchange is disabled because service-secret is blank service={}", serviceName);
            return null;
        }
        try {
            String payload = MAPPER.writeValueAsString(
                    java.util.Map.of("service", serviceName, "secret", serviceSecret));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String token = extractToken(response.body());
                if (token != null) return token;
                warn("Service token response has no token url={} body={}", url, truncate(response.body()));
                return null;
            }
            warn("Service token exchange rejected url={} status={} body={}",
                    url, response.statusCode(), truncate(response.body()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            warn("Service token exchange interrupted url={}", url);
        } catch (IOException | IllegalArgumentException failure) {
            warn("Service token exchange failed url={} error={}", url, failure.toString());
        }
        return null;
    }

    private void warn(String format, Object... arguments) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < 60_000L) return;
        lastWarnAt = now;
        log.warn(format + " (same warning suppressed for 60s)", arguments);
    }

    static String extractToken(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            String token = MAPPER.readTree(body).path("token").asText(null);
            return token == null || token.isBlank() ? null : token;
        } catch (JsonProcessingException invalidJson) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
