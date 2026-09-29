package com.socp.search.config.render;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the rendered mixed-source config with the repository-pinned Vector image. */
@EnabledIfEnvironmentVariable(named = "SOCP_TESTCONTAINERS", matches = "true")
class VectorConfigRendererContainerTest {

    @TempDir Path temporaryDirectory;

    @Test
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
}
