package com.socp.ai.infrastructure.llm;

import com.socp.ai.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpLlmChatClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndReadsAssistantContent() throws Exception {
        String[] authorization = new String[1];
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization[0] = exchange.getRequestHeaders().getFirst("Authorization");
            read(exchange);
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"  isolate host  \"}}]}");
        });
        server.start();

        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/");
        properties.setAllowedHosts(List.of("localhost"));
        properties.setHttpsOnly(false);
        properties.setAllowPrivateNetworks(true);
        properties.setApiKey("test-key");
        properties.setModel("test-model");

        assertThat(new HttpLlmChatClient(properties).chat("what happened?"))
                .contains("isolate host");
        assertThat(authorization[0]).isEqualTo("Bearer test-key");
    }

    @Test
    void returnsEmptyForDisabledMalformedAndNonSuccessResponses() throws Exception {
        LlmProperties disabled = new LlmProperties();
        assertThat(new HttpLlmChatClient(disabled).chat("question")).isEmpty();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            read(exchange);
            respond(exchange, 502, "unavailable");
        });
        server.start();
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setAllowedHosts(List.of("localhost"));
        properties.setHttpsOnly(false);
        properties.setAllowPrivateNetworks(true);
        assertThat(new HttpLlmChatClient(properties).chat("question")).isEmpty();
    }

    @Test
    void rejectsAnEndpointThatIsNotAllowlistedBeforeConnecting() {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://localhost:1");
        properties.setAllowedHosts(List.of("another-host.invalid"));
        properties.setHttpsOnly(false);

        assertThat(new HttpLlmChatClient(properties).chat("question")).isEmpty();
    }

    @Test
    void actualChatPathConnectsToPinnedAddressForOtherwiseUnresolvableHost() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            read(exchange); respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"pinned reply\"}}]}");
        }); server.start();
        String host = "llm-pinned-fixture.invalid";
        var properties = new LlmProperties(); properties.setEnabled(true);
        properties.setBaseUrl("http://" + host + ":" + server.getAddress().getPort());
        properties.setAllowedHosts(List.of(host)); properties.setHttpsOnly(false); properties.setAllowPrivateNetworks(true);
        com.socp.platform.client.http.PinnedDnsResolverProvider.pin(host,
                new java.net.InetAddress[] { java.net.InetAddress.getByName("127.0.0.1") });
        try {
            assertThat(new HttpLlmChatClient(properties).chat("question")).contains("pinned reply");
        } finally { com.socp.platform.client.http.PinnedDnsResolverProvider.unpin(host); }
    }

    @Test
    void interruptionOfActualChatPreservesInterruptedFlag() throws Exception {
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            read(exchange); arrived.countDown();
            try { release.await(4, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        var properties = new LlmProperties(); properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAllowedHosts(List.of("127.0.0.1")); properties.setHttpsOnly(false); properties.setAllowPrivateNetworks(true);
        var interruptedFlag = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            new HttpLlmChatClient(properties).chat("question");
            interruptedFlag.set(Thread.currentThread().isInterrupted());
        });
        try {
            caller.start(); assertThat(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            caller.interrupt(); caller.join(1500);
            assertThat(caller.isAlive()).isFalse(); assertThat(interruptedFlag).isTrue();
        } finally { release.countDown(); caller.interrupt(); }
    }

    private static void read(HttpExchange exchange) throws IOException {
        try (var input = exchange.getRequestBody()) {
            input.readAllBytes();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
