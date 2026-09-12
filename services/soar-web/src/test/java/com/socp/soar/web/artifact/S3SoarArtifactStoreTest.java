package com.socp.soar.web.artifact;

import com.socp.soar.web.config.SoarArtifactProperties;
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

class S3SoarArtifactStoreTest {
    private HttpServer server;
    private AtomicReference<byte[]> object;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestPath;

    @BeforeEach
    void setUp() throws IOException {
        object = new AtomicReference<>();
        authorization = new AtomicReference<>();
        requestPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void storesReadsAndDeletesWithSignedPathStyleRequests() {
        SoarArtifactProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        S3SoarArtifactStore store = new S3SoarArtifactStore(properties,
                reference -> Optional.of(reference.contains("access") ? "access-key" : "secret-key"));

        SoarArtifactStore.StoredArtifact saved = store.put("tenant/a", "run-1", "artifact-1",
                "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        assertThat(saved.storageRef()).isEqualTo("s3://soar-artifacts/soar/x-584a11c77f870c594a006addb001b6af9cfb03baca2d4081942d67565a14a245/run-1/artifact-1");
        assertThat(saved.sha256()).hasSize(64);
        assertThat(requestPath.get()).isEqualTo("/soar-artifacts/soar/x-584a11c77f870c594a006addb001b6af9cfb03baca2d4081942d67565a14a245/run-1/artifact-1");
        assertThat(authorization.get()).startsWith("AWS4-HMAC-SHA256 Credential=access-key/")
                .contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date");
        assertThat(new String(store.read(saved.storageRef()).orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("{\"ok\":true}");

        store.delete(saved.storageRef());

        assertThat(store.read(saved.storageRef())).isEmpty();
    }

    @Test
    void refusesHttpEndpointUnlessExplicitlyAllowed() {
        SoarArtifactProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setAllowInsecure(false);

        assertThatIllegalStateException().isThrownBy(() -> new S3SoarArtifactStore(properties,
                reference -> Optional.of("secret")))
                .withMessageContaining("must use HTTPS");
    }

    @Test
    void refusesUnsupportedEndpointSchemesEvenWhenInsecureTransportIsAllowed() {
        SoarArtifactProperties properties = properties("ftp://objects.example.test");
        properties.setAllowInsecure(true);

        assertThatIllegalStateException().isThrownBy(() -> new S3SoarArtifactStore(properties,
                reference -> Optional.of("secret")))
                .withMessageContaining("endpoint is invalid");
    }

    @Test
    void hashesUnsafeIdentifiersToAvoidCrossTenantStorageKeyCollisions() {
        SoarArtifactProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort());
        S3SoarArtifactStore store = new S3SoarArtifactStore(properties,
                reference -> Optional.of("secret"));

        SoarArtifactStore.StoredArtifact slash = store.put("tenant/a", "run", "one", null,
                new byte[] {1});
        SoarArtifactStore.StoredArtifact underscore = store.put("tenant_a", "run", "one", null,
                new byte[] {1});

        assertThat(slash.storageRef()).isNotEqualTo(underscore.storageRef());
        assertThat(slash.storageRef()).contains("x-");
    }

    @Test
    void refusesEndpointWithQueryOrBlankResolvedCredentials() {
        SoarArtifactProperties query = properties("https://objects.example.test/prefix?redirect=1");
        query.setAllowInsecure(false);
        assertThatIllegalStateException().isThrownBy(() -> new S3SoarArtifactStore(query,
                reference -> Optional.of("secret")))
                .withMessageContaining("endpoint is invalid");

        SoarArtifactProperties valid = properties("https://objects.example.test");
        valid.setAllowInsecure(false);
        S3SoarArtifactStore store = new S3SoarArtifactStore(valid,
                reference -> Optional.of("   "));
        assertThatIllegalStateException().isThrownBy(() -> store.put("tenant", "run", "artifact",
                "application/json", new byte[0]))
                .withMessageContaining("ACCESS_KEY_UNAVAILABLE");
    }

    private SoarArtifactProperties properties(String endpoint) {
        SoarArtifactProperties properties = new SoarArtifactProperties();
        properties.setEndpoint(endpoint);
        properties.setBucket("soar-artifacts");
        properties.setRegion("us-east-1");
        properties.setAccessKeyRef("env://access");
        properties.setSecretKeyRef("env://secret");
        properties.setAllowInsecure(true);
        return properties;
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestPath.set(exchange.getRequestURI().getRawPath());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = exchange.getRequestBody().readAllBytes();
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                object.set(body);
                respond(exchange, 200, new byte[0]);
            }
            case "GET" -> {
                byte[] stored = object.get();
                respond(exchange, stored == null ? 404 : 200, stored == null ? new byte[0] : stored);
            }
            case "DELETE" -> {
                object.set(null);
                respond(exchange, 204, new byte[0]);
            }
            default -> respond(exchange, 405, new byte[0]);
        }
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
