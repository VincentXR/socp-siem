package com.socp.search.config.render;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.Testcontainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.parser.CanonicalEvent;
import com.sun.net.httpserver.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Executes generated config with the pinned Vector image or an explicitly supplied pinned native binary. */
class VectorConfigRendererContainerTest {

    @TempDir Path temporaryDirectory;

    @Test
    @EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
    void mixedConfigValidatesAndReadsAnOldFileFromBeginning() throws Exception {
        Path historical = temporaryDirectory.resolve("historical.log");
        Files.writeString(historical,
                "{\"eventId\":\"historical-vector-event\",\"message\":\""
                        + "x".repeat(512) + "\"}\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(historical, FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        LogSource file = LogSource.createFull("historical-file", SourceType.FILE, ParseFormat.JSON,
                "/fixture/historical.log", null, null, "test", true,
                "beginning", null, null, List.of(), null,
                null, "utf-8", "timestamp", "UTC", List.of(), 1, null, null, null);
        LogSource external = LogSource.createFull("windows-agent", SourceType.WINDOWS_EVENT, ParseFormat.JSON,
                null, null, null, "test", true,
                "beginning", null, null, List.of(), null,
                null, "utf-8", "timestamp", "UTC", List.of(), 1, null, null, null);
        SinkTarget target = new SinkTarget("test-sink", "test", "HTTP",
                "http://127.0.0.1:9/ingest",
                null, true, Instant.now());
        String config = new VectorConfigRenderer(null).render(
                List.of(file, external), ignored -> target, true);
        String transform = "t_" + file.id().replace('-', '_');
        config += """

                # Test-only observer: the generated HTTP sink remains in the topology and
                # validation, while stdout proves the historical file was actually read.
                [sinks.historical_file_observer]
                type = "console"
                inputs = ["%s"]
                target = "stdout"
                encoding.codec = "json"
                """.formatted(transform);
        Path configFile = temporaryDirectory.resolve("vector.toml");
        Files.writeString(configFile, config, StandardCharsets.UTF_8);

        try (GenericContainer<?> vector = new GenericContainer<>(
                DockerImageName.parse(MiddlewareImages.image("vector")))) {
            vector.withCopyFileToContainer(MountableFile.forHostPath(configFile),
                            "/etc/vector/vector.toml")
                    .withCopyFileToContainer(MountableFile.forHostPath(historical),
                            "/fixture/historical.log")
                    // Vector runs as root in the image. Keeping checkpoints in a container
                    // tmpfs avoids leaving root-owned files in JUnit's host temp directory.
                    .withTmpFs(Map.of("/.cache/vector", "rw"))
                    .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh"))
                    .withCommand("-c",
                            "touch -t 202001010000 /fixture/historical.log"
                                    + " && vector validate --no-environment /etc/vector/vector.toml"
                                    + " && exec vector --config /etc/vector/vector.toml");
            try {
                vector.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!vector.getLogs().contains("historical-vector-event")
                        && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                }
                assertTrue(vector.getLogs().contains("historical-vector-event"),
                        () -> "Vector did not deliver the historical file:\n" + vector.getLogs());
            } finally {
                vector.stop();
            }
        }
    }

    @Test
    @EnabledIf("vectorRuntimeAvailable")
    void httpSinkRetriesBeyondFiveFailuresThenDeliversAnUnchangedAutoEnvelope() throws Exception {
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var acknowledged = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ingest", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = attempts.incrementAndGet() <= 8 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            if (status == 200) acknowledged.countDown();
        });
        server.start();
        try {
            String nativeBinary = System.getenv("SOCP_VECTOR_BIN");
            boolean nativeRuntime = nativeBinary != null && !nativeBinary.isBlank();
            if (!nativeRuntime) Testcontainers.exposeHostPorts(server.getAddress().getPort());
            Path input = temporaryDirectory.resolve("retry.log");
            String raw = new ObjectMapper().writeValueAsString(Map.of(
                    "eventId", "retry-event", "source", "auth", "host", "auth-host",
                    "src_ip", "203.0.113.9", "message", "Failed password " + "x".repeat(512)));
            Files.writeString(input, raw + "\n", StandardCharsets.UTF_8);
            LogSource source = LogSource.createFull("retry-file", SourceType.FILE, ParseFormat.AUTO,
                    nativeRuntime ? input.toAbsolutePath().toString() : "/fixture/retry.log", null, null, "test", true,
                    "beginning", null, null, List.of(), null,
                    null, "utf-8", "timestamp", "UTC", List.of(), 1, null, null, null);
            SinkTarget target = new SinkTarget("retry-sink", "test", "HTTP",
                    "http://" + (nativeRuntime ? "127.0.0.1" : "host.testcontainers.internal")
                            + ":" + server.getAddress().getPort() + "/ingest",
                    null, true, Instant.now());
            // Keep the production retry budget. Shorten only the delay so this
            // regression reaches attempt nine without a multi-minute test.
            String config = new VectorConfigRenderer(null).render(List.of(source), ignored -> target, true)
                    .replace("request.retry_initial_backoff_secs = 2", "request.retry_initial_backoff_secs = 1")
                    .replace("request.retry_max_duration_secs = 30", "request.retry_max_duration_secs = 1");
            Path configFile = temporaryDirectory.resolve("retry.toml");
            if (nativeRuntime) {
                Path state = Files.createDirectories(temporaryDirectory.resolve("state"));
                Files.createDirectories(temporaryDirectory.resolve(".cache/vector"));
                config = "data_dir = \"" + state.toAbsolutePath() + "\"\n" + config;
            }
            Files.writeString(configFile, config, StandardCharsets.UTF_8);
            if (nativeRuntime) {
                runNativeUntilAcknowledged(nativeBinary, configFile, acknowledged);
            } else try (GenericContainer<?> vector = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.image("vector")))) {
                vector.withCopyFileToContainer(MountableFile.forHostPath(configFile), "/etc/vector/vector.toml")
                        .withCopyFileToContainer(MountableFile.forHostPath(input), "/fixture/retry.log")
                        .withTmpFs(Map.of("/.cache/vector", "rw"))
                        .withEnv("VECTOR_LOG", "debug")
                        .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh"))
                        .withCommand("-c", "vector validate --no-environment /etc/vector/vector.toml"
                                + " && exec vector --config /etc/vector/vector.toml");
                vector.start();
                var probe = vector.execInContainer("wget", "-q", "-T", "5", "-O", "/dev/null",
                        "http://host.testcontainers.internal:" + server.getAddress().getPort() + "/ready");
                assertEquals(0, probe.getExitCode(),
                        () -> "Vector fixture endpoint is unreachable: " + probe.getStderr());
                assertTrue(acknowledged.await(60, TimeUnit.SECONDS),
                        () -> "Vector did not recover; received HTTP attempts=" + attempts.get()
                                + " (expected eight 503 responses then one 200):\n" + vector.getLogs());
            }
            assertEquals(9, attempts.get());
            assertEquals(1, received.stream().distinct().count(), "buffered event identity must survive retries");
            var canonical = new ParserRegistry().parse(received.getLast().strip(), ParseFormat.AUTO, null);
            assertNull(canonical.get("parse.error"));
            assertEquals("retry-event", canonical.get("eventId"));
            assertEquals("auth", canonical.get("source"));
            assertEquals("203.0.113.9", canonical.get(CanonicalEvent.SOURCE_IP));
        } finally {
            server.stop(0);
        }
    }

    private static boolean vectorRuntimeAvailable() {
        String binary = System.getenv("SOCP_VECTOR_BIN");
        return "true".equals(System.getenv("SOCP_TESTCONTAINERS")) || (binary != null && !binary.isBlank());
    }

    private void runNativeUntilAcknowledged(String binary, Path config,
                                            java.util.concurrent.CountDownLatch acknowledged) throws Exception {
        Process version = new ProcessBuilder(binary, "--version").start();
        String actualVersion = new String(version.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, version.waitFor());
        String image = MiddlewareImages.image("vector");
        String pinnedVersion = image.substring(image.lastIndexOf(':') + 1).split("-")[0];
        assertTrue(actualVersion.startsWith("vector " + pinnedVersion + " "), actualVersion);
        Path logs = temporaryDirectory.resolve("vector.log");
        Process validate = new ProcessBuilder(binary, "validate", "--no-environment", config.toString())
                .directory(temporaryDirectory.toFile()).redirectErrorStream(true).redirectOutput(logs.toFile()).start();
        assertEquals(0, validate.waitFor(), () -> readLogs(logs));
        Process vector = new ProcessBuilder(binary, "--config", config.toString())
                .directory(temporaryDirectory.toFile()).redirectErrorStream(true).redirectOutput(logs.toFile()).start();
        try {
            assertTrue(acknowledged.await(60, TimeUnit.SECONDS), () -> readLogs(logs));
        } finally {
            vector.destroy();
            if (!vector.waitFor(5, TimeUnit.SECONDS)) vector.destroyForcibly().waitFor();
        }
    }

    private static String readLogs(Path logs) {
        try { return Files.readString(logs); }
        catch (java.io.IOException error) { return error.toString(); }
    }
}
