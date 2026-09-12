package com.socp.soar.web.connector;

import com.socp.soar.web.config.SoarSecretProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class VaultSecretResolverTest {
    private HttpServer server;
    private AtomicReference<String> response;
    private AtomicReference<String> token;

    @BeforeEach
    void setUp() throws IOException {
        response = new AtomicReference<>("{\"data\":{\"data\":{\"token\":\"first\"}}}");
        token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsKvFieldAndObservesRotation() {
        SoarSecretProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        VaultSecretResolver resolver = new VaultSecretResolver(properties, new ObjectMapper(),
                reference -> Optional.of("vault-token"));

        assertThat(resolver.resolve("vault://secret/data/edr#token")).contains("first");
        assertThat(token.get()).isEqualTo("vault-token");

        response.set("{\"data\":{\"token\":\"rotated\"}}");
        assertThat(resolver.resolve("vault://secret/data/edr#token")).contains("rotated");
    }

    @Test
    void rejectsMalformedReferencesAndUnavailableTokens() {
        SoarSecretProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        VaultSecretResolver resolver = new VaultSecretResolver(properties, new ObjectMapper(),
                reference -> Optional.empty());

        assertThat(resolver.resolve("vault://secret/data/edr")).isEmpty();
        assertThat(resolver.resolve("vault://secret/../edr#token")).isEmpty();
        assertThat(resolver.resolve("vault://secret/data/edr#token?x=1")).isEmpty();
        assertThat(resolver.resolve("env://MISSING_VAULT_TOKEN")).isEmpty();
    }

    @Test
    void requiresHttpsUnlessExplicitlyAllowed() {
        SoarSecretProperties properties = properties("http://vault.example.test");
        properties.setAllowInsecure(false);
        assertThatIllegalStateException().isThrownBy(() -> new VaultSecretResolver(properties,
                new ObjectMapper(), reference -> Optional.of("token")))
                .withMessageContaining("must use HTTPS");
    }

    @Test
    void refusesUnsupportedEndpointSchemesEvenWhenInsecureTransportIsAllowed() {
        SoarSecretProperties properties = properties("ftp://vault.example.test");
        properties.setAllowInsecure(true);

        assertThatIllegalStateException().isThrownBy(() -> new VaultSecretResolver(properties,
                new ObjectMapper(), reference -> Optional.of("token")))
                .withMessageContaining("endpoint is invalid");
    }

    private SoarSecretProperties properties(String endpoint) {
        SoarSecretProperties properties = new SoarSecretProperties();
        properties.setVaultEndpoint(endpoint);
        properties.setVaultTokenRef("env://VAULT_TOKEN");
        properties.setAllowInsecure(true);
        return properties;
    }

    private void handle(HttpExchange exchange) throws IOException {
        token.set(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
        byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
