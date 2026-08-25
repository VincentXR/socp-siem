package com.socp.ai.infrastructure.llm;

import com.socp.ai.config.LlmProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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
        assertThat(new HttpLlmChatClient(properties).chat("question")).isEmpty();
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
