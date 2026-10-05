package com.socp.platform.client.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A fresh request-scoped connection pool with no DNS or pooled-connection escape hatch. */
public final class PinnedHttpTransport {
    public record Response(int status, String body, Map<String, String> headers) {
        public Response(int status, String body) { this(status, body, Map.of()); }
        public Response { headers = Map.copyOf(headers); }
        public String header(String name) { return headers.get(name.toLowerCase(java.util.Locale.ROOT)); }
    }
    private final SSLContext sslContext;
    public PinnedHttpTransport() { this(null); }
    PinnedHttpTransport(SSLContext sslContext) { this.sslContext = sslContext; }

    public Response send(String method, URI uri, byte[] body, String contentType, Map<String, String> headers,
                  PinnedEndpoint pinned, int connectTimeoutMs, int requestTimeoutMs, int responseLimit) throws Exception {
        if (body != null && body.length > 16 * 1024 * 1024)
            throw new IllegalArgumentException("External request body exceeds 16 MiB limit");
        if (pinned == null || pinned.isRejected() || pinned.addresses().length == 0)
            throw new IllegalArgumentException("External address validation is required");
        String normalized = uri.getHost().replaceFirst("\\.$", "");
        if (!pinned.host().equalsIgnoreCase(normalized)) throw new IllegalArgumentException("Pinned host mismatch");
        InetAddress[] addresses = pinned.addresses();
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!pinned.host().equalsIgnoreCase(host.replaceFirst("\\.$", ""))) throw new UnknownHostException("Unvalidated hostname");
                return addresses.clone();
            }
            @Override public String resolveCanonicalHostname(String host) throws UnknownHostException {
                resolve(host); return pinned.host();
            }
        };
        var tls = SSLConnectionSocketFactoryBuilder.create();
        if (sslContext != null) tls.setSslContext(sslContext);
        // DefaultHostnameVerifier and original URI hostname retain HTTPS identity checks and SNI.
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setSSLSocketFactory(tls.build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1, connectTimeoutMs)))
                        .setSocketTimeout(Timeout.ofMilliseconds(Math.max(1, requestTimeoutMs))).build()).build();
        var client = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
                .disableRedirectHandling().disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(Math.max(1, requestTimeoutMs)))
                        .setResponseTimeout(Timeout.ofMilliseconds(Math.max(1, requestTimeoutMs))).build()).build();
        var request = new HttpUriRequestBase(method, uri);
        headers.forEach(request::setHeader);
        if (body != null) request.setEntity(new ByteArrayEntity(body,
                ContentType.parse(contentType == null ? "application/json" : contentType)));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var pending = executor.submit(() -> client.execute(request, response -> {
            Map<String, String> responseHeaders = new java.util.LinkedHashMap<>();
            for (var header : response.getHeaders()) {
                if (responseHeaders.size() >= 100 || header.getValue().length() > 8192)
                    throw new IllegalArgumentException("External response headers exceed limit");
                responseHeaders.putIfAbsent(header.getName().toLowerCase(java.util.Locale.ROOT), header.getValue());
            }
            if (response.getEntity() == null) return new Response(response.getCode(), "", responseHeaders);
            int maximum = Math.max(1, Math.min(16 * 1024 * 1024, responseLimit));
            try (var input = response.getEntity().getContent()) {
                byte[] bytes = input.readNBytes(maximum + 1);
                if (bytes.length > maximum) { request.cancel(); throw new ResponseBodyTooLargeException(maximum); }
                return new Response(response.getCode(), new String(bytes, StandardCharsets.UTF_8), responseHeaders);
            }
        }));
        try {
            return pending.get(Math.max(1, requestTimeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            request.cancel(); pending.cancel(true); throw interrupted;
        } catch (TimeoutException timedOut) {
            request.cancel(); pending.cancel(true);
            throw new java.net.http.HttpTimeoutException("External request deadline exceeded");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException("External transport failed", failed.getCause());
        } finally {
            request.cancel(); client.close(CloseMode.IMMEDIATE); executor.shutdownNow();
        }
    }
}
