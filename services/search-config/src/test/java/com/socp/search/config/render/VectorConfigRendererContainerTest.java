package com.socp.search.config.render;

import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.Testcontainers;
import com.socp.search.config.parser.ParserRegistry;
import com.socp.search.config.parser.CanonicalEvent;

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
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        try (var fixture = new VectorDeliveryFixture(temporaryDirectory, "retry", 8)) {
            fixture.writeInput(VectorDeliveryFixture.event("retry-event"));
            fixture.start();
            fixture.awaitAccepted(1);
            fixture.assertDiskRecordExists();
            fixture.assertQuiet(1);
            var received = fixture.received();
            assertEquals(9, received.size(), "expected eight 503 responses then one 200");
            assertTrue(received.subList(0, 8).stream().allMatch(attempt -> attempt.status() == 503));
            assertEquals(200, received.getLast().status());
            assertEquals(1, received.stream().map(VectorDeliveryFixture.Attempt::body).distinct().count(),
                    "buffered event identity and every envelope byte must survive retries");
            assertEvent(received.getLast().body(), "retry-event");
        }
    }

    @RepeatedTest(3)
    @EnabledIf("vectorRuntimeAvailable")
    void coldQuietSingleEventReachesHttpWithoutAnotherSourceWrite(RepetitionInfo repetition) throws Exception {
        String id = "cold-event-" + repetition.getCurrentRepetition();
        try (var fixture = new VectorDeliveryFixture(temporaryDirectory, "cold-" + repetition.getCurrentRepetition(), 0)) {
            fixture.writeInput(VectorDeliveryFixture.event(id));
            fixture.start();
            fixture.awaitAccepted(1);
            fixture.assertDiskRecordExists();
            fixture.assertQuiet(1);
            assertEquals(1, fixture.received().size());
            assertEvent(fixture.acceptedBodies().getFirst(), id);
        }
    }

    @Test
    @EnabledIf("vectorRuntimeAvailable")
    void eachIdleTailEventArrivesBeforeTheNextEventIsWritten() throws Exception {
        try (var fixture = new VectorDeliveryFixture(temporaryDirectory, "idle-tail", 0)) {
            fixture.writeInput(VectorDeliveryFixture.event("tail-event-0"));
            fixture.start();
            for (int event = 0; event < 4; event++) {
                if (event > 0) fixture.appendInput(VectorDeliveryFixture.event("tail-event-" + event));
                fixture.awaitAccepted(event + 1);
                assertEquals(event + 1, fixture.received().size(), "no extra HTTP requests or synthetic keepalive events");
                assertEvent(fixture.acceptedBodies().get(event), "tail-event-" + event);
                fixture.assertQuiet(event + 1);
            }
            fixture.assertDiskRecordExists();
            assertEquals(4, fixture.acceptedBodies().stream().distinct().count());
        }
    }

    @Test
    @EnabledIf("vectorRuntimeAvailable")
    void unacknowledgedDiskEventReplaysAfterCrashWithTheIdenticalEnvelope() throws Exception {
        try (var fixture = new VectorDeliveryFixture(temporaryDirectory, "restart-replay", 0)) {
            fixture.setOutage(true);
            fixture.writeInput(VectorDeliveryFixture.event("restart-event"));
            fixture.start();
            fixture.awaitFailedAttempt();
            fixture.assertDiskRecordExists();
            String bufferedEnvelope = fixture.received().getFirst().body();
            assertEvent(bufferedEnvelope, "restart-event");
            assertTrue(fixture.acceptedBodies().isEmpty());
            fixture.crash();
            // Remove the original input entirely: success must come from the
            // retained disk buffer, not source rereading or a new transform/UUID.
            // This proves process-crash replay, not power-loss/fsync durability.
            fixture.removeInput();
            fixture.assertDiskRecordExists();
            fixture.start();
            fixture.awaitFailedAttempt();
            assertTrue(fixture.received().stream().filter(attempt -> attempt.generation() == 2)
                    .allMatch(attempt -> attempt.body().equals(bufferedEnvelope)));
            fixture.setOutage(false);
            fixture.awaitAccepted(1);
            fixture.assertQuiet(1);
            assertEquals(1, fixture.acceptedBodies().size());
            assertEquals(bufferedEnvelope, fixture.acceptedBodies().getFirst());
            assertEquals(1, fixture.received().stream().map(VectorDeliveryFixture.Attempt::body).distinct().count());
            assertEvent(fixture.acceptedBodies().getFirst(), "restart-event");
        }
    }

    private static void assertEvent(String body, String id) {
        assertEquals(1, body.lines().filter(line -> !line.isBlank()).count(), "exactly one NDJSON event per quiet batch");
        var canonical = new ParserRegistry().parse(body.strip(), ParseFormat.AUTO, null);
        assertNull(canonical.get("parse.error"));
        assertEquals(id, canonical.get("eventId"));
        assertEquals("auth", canonical.get("source"));
        assertEquals("auth-host", canonical.get(CanonicalEvent.HOST_NAME));
        assertEquals("203.0.113.9", canonical.get(CanonicalEvent.SOURCE_IP));
        assertEquals("Failed password " + "x".repeat(512), canonical.get(CanonicalEvent.EVENT_MESSAGE));
        assertFalse(body.isBlank());
    }

    private static boolean vectorRuntimeAvailable() { return VectorDeliveryFixture.available(); }
}
