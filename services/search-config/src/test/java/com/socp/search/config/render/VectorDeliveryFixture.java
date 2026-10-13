package com.socp.search.config.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.test.MiddlewareImages;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import com.sun.net.httpserver.HttpServer;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture-only HTTP endpoint and persistent Vector runtime; no production endpoint or credentials. */
final class VectorDeliveryFixture implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DELIVERY_TIMEOUT_SECONDS = 60;
    private final String binary = System.getenv("SOCP_VECTOR_BIN");
    private final boolean nativeRuntime = binary != null && !binary.isBlank();
    private final Path directory;
    private final Path input;
    private final Path configFile;
    private final Path evidence;
    private final HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicBoolean outage = new AtomicBoolean();
    private final List<Attempt> received = new CopyOnWriteArrayList<>();
    private final int failuresBeforeSuccess;
    private GenericContainer<?> container;
    private Process process;
    private Path logs;
    private int generation;
    private volatile int activeGeneration;

    record Attempt(int number, int generation, int status, String receivedAt, String body) {}

    VectorDeliveryFixture(Path root, String name, int failuresBeforeSuccess) throws Exception {
        this.failuresBeforeSuccess = failuresBeforeSuccess;
        directory = Files.createDirectories(root.resolve(name));
        input = directory.resolve("input.log");
        configFile = directory.resolve("vector.toml");
        evidence = Files.createDirectories(Path.of("target", "vector-delivery-evidence",
                name + "-" + root.getFileName()));
        Files.writeString(evidence.resolve("runtime-selection.txt"), nativeRuntime
                ? "native_binary=" + binary + "\n" : "container_image=" + MiddlewareImages.image("vector") + "\n");
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/ready", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ingest", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int number = attempts.incrementAndGet();
            int status = outage.get() || number <= failuresBeforeSuccess ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            received.add(new Attempt(number, activeGeneration, status, Instant.now().toString(), body));
        });
        server.start();
        if (!nativeRuntime) Testcontainers.exposeHostPorts(server.getAddress().getPort());
        String sourcePath = nativeRuntime ? input.toAbsolutePath().toString() : "/fixture/input.log";
        LogSource source = LogSource.createFull(name, SourceType.FILE, ParseFormat.AUTO,
                sourcePath, null, null, "test", true, "beginning", null, null, List.of(), null,
                null, "utf-8", "timestamp", "UTC", List.of(), 1, null, null, null);
        SinkTarget target = new SinkTarget("delivery-sink", "test", "HTTP",
                "http://" + (nativeRuntime ? "127.0.0.1" : "host.testcontainers.internal")
                        + ":" + server.getAddress().getPort() + "/ingest", null, true, Instant.now());
        Path state = Files.createDirectories(directory.resolve("state"));
        Path checkpoints = Files.createDirectories(state.resolve("checkpoints"));
        Path buffers = Files.createDirectories(state.resolve("buffers"));
        // Production topology and disk/retry settings are unchanged. Only fixture paths
        // and retry delays are replaced so eight failures fit the original 60-second bound.
        String config = new VectorConfigRenderer(null).render(List.of(source), ignored -> target, true)
                .replace("request.retry_initial_backoff_secs = 2", "request.retry_initial_backoff_secs = 1")
                .replace("request.retry_max_duration_secs = 30", "request.retry_max_duration_secs = 1")
                .replace("data_dir = \".cache/vector\"", "data_dir = \""
                        + toml(nativeRuntime ? checkpoints.toAbsolutePath().toString() : "/state/checkpoints") + "\"");
        config = "data_dir = \"" + toml(nativeRuntime ? buffers.toAbsolutePath().toString() : "/state/buffers")
                + "\"\n" + config;
        assertTrue(config.contains("buffer.type = \"disk\""));
        assertTrue(config.contains("buffer.when_full = \"block\""));
        Files.writeString(configFile, config, StandardCharsets.UTF_8);
        Files.copy(configFile, evidence.resolve("fixture-config.toml"));
    }

    static boolean available() {
        String binary = System.getenv("SOCP_VECTOR_BIN");
        return "true".equals(System.getenv("SOCP_TESTCONTAINERS")) || (binary != null && !binary.isBlank());
    }

    static String event(String id) throws Exception {
        return JSON.writeValueAsString(Map.of("eventId", id, "source", "auth", "host", "auth-host",
                "src_ip", "203.0.113.9", "message", "Failed password " + "x".repeat(512)));
    }

    void writeInput(String raw) throws IOException {
        Files.writeString(input, raw + "\n", StandardCharsets.UTF_8);
    }

    void appendInput(String raw) throws Exception {
        Files.writeString(input, raw + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        if (!nativeRuntime) {
            checkedExec("sh", "-c", "printf '%s\\n' \"$1\" >> /fixture/input.log", "fixture-append", raw);
        }
    }

    void removeInput() throws Exception {
        Files.delete(input);
        if (!nativeRuntime) checkedExec("rm", "/fixture/input.log");
    }

    void setOutage(boolean unavailable) { outage.set(unavailable); }
    List<Attempt> received() { return List.copyOf(received); }
    List<String> acceptedBodies() {
        return received.stream().filter(attempt -> attempt.status() == 200).map(Attempt::body).toList();
    }

    void start() throws Exception {
        activeGeneration = ++generation;
        logs = directory.resolve("vector-" + generation + ".log");
        if (nativeRuntime) {
            if (generation == 1) {
                Path versionOutput = evidence.resolve("runtime-version.txt");
                Process version = new ProcessBuilder(binary, "--version").redirectErrorStream(true)
                        .redirectOutput(versionOutput.toFile()).start();
                awaitCommand(version, 10, versionOutput, "Vector --version");
                String actualVersion = read(versionOutput);
                String image = MiddlewareImages.image("vector");
                String pinnedVersion = image.substring(image.lastIndexOf(':') + 1).split("-")[0];
                assertTrue(actualVersion.startsWith("vector " + pinnedVersion + " "), actualVersion);
                Path validationOutput = evidence.resolve("validation.log");
                Process validate = builder("validate", "--no-environment", configFile.toAbsolutePath().toString())
                        .redirectOutput(validationOutput.toFile()).start();
                awaitCommand(validate, 30, validationOutput, "Vector validation");
            }
            process = builder("--config", configFile.toAbsolutePath().toString()).redirectOutput(logs.toFile()).start();
        } else {
            if (container == null) {
                container = new GenericContainer<>(DockerImageName.parse(MiddlewareImages.image("vector")))
                        .withCopyFileToContainer(MountableFile.forHostPath(configFile), "/fixture/vector.toml")
                        .withCopyFileToContainer(MountableFile.forHostPath(input), "/fixture/input.log")
                        // The container lives across process crashes. Its private tmpfs
                        // preserves buffer/checkpoints without root-owned host artifacts.
                        .withTmpFs(Map.of("/state", "rw"))
                        .withEnv("VECTOR_LOG", "trace")
                        .withEnv("VECTOR_LOG_COLOR", "false")
                        .withCreateContainerCmdModifier(command -> command.withEntrypoint("/bin/sh"))
                        .withCommand("-c", "mkdir -p /state/checkpoints /state/buffers; while :; do sleep 3600; done");
                container.start();
                checkedExec("vector", "validate", "--no-environment", "/fixture/vector.toml");
                checkedExec("wget", "-q", "-T", "5", "-O", "/dev/null",
                        "http://host.testcontainers.internal:" + server.getAddress().getPort() + "/ready");
                Files.writeString(evidence.resolve("runtime-version.txt"), checkedExec("vector", "--version"));
            }
            checkedExec("sh", "-c", "nohup vector --config /fixture/vector.toml </dev/null > /fixture/vector-"
                    + generation + ".log 2>&1 & echo $! > /fixture/vector.pid");
        }
        snapshot("started");
    }

    void awaitAccepted(int count) throws Exception {
        await(() -> acceptedBodies().size() >= count, "expected " + count + " acknowledged HTTP bodies");
    }

    void awaitFailedAttempt() throws Exception {
        await(() -> received.stream().anyMatch(attempt -> attempt.generation() == generation && attempt.status() == 503),
                "expected an HTTP 503 in runtime generation " + generation);
    }

    private void await(BooleanSupplier condition, String expectation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DELIVERY_TIMEOUT_SECONDS);
        long nextSnapshot = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean observedBeforeDeadline = false;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                observedBeforeDeadline = true;
                break;
            }
            if (nativeRuntime && !process.isAlive()) break;
            if (System.nanoTime() >= nextSnapshot) {
                snapshot("waiting: " + expectation);
                nextSnapshot = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            }
            Thread.sleep(100);
        }
        // Freeze the deadline decision before slow Docker/log diagnostics. A late
        // acknowledgement during snapshot or cleanup must not turn failure green.
        String diagnostic = snapshot(observedBeforeDeadline ? "observed: " + expectation : "FAILED: " + expectation);
        assertTrue(observedBeforeDeadline, () -> expectation + " within " + DELIVERY_TIMEOUT_SECONDS + " seconds\n" + diagnostic);
    }

    void assertQuiet(int expectedAccepted) throws Exception {
        // Longer than the generated two-second batch timeout. No witness/keepalive
        // event is written: the previous event must arrive on its own before this wait.
        Thread.sleep(3_000);
        assertEquals(expectedAccepted, acceptedBodies().size(), () -> snapshotUnchecked("unexpected HTTP duplicate"));
        snapshot("quiet after " + expectedAccepted + " acknowledgements");
    }

    void assertDiskRecordExists() throws Exception {
        if (nativeRuntime) {
            try (var files = Files.walk(directory.resolve("state/buffers"))) {
                assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("buffer-data-")
                                && size(path) > 0), () -> snapshotUnchecked("missing disk record"));
            }
        } else {
            assertTrue(!checkedExec("sh", "-c", "find /state/buffers -name 'buffer-data-*' -type f -size +0c -print").isBlank(),
                    () -> snapshotUnchecked("missing disk record"));
        }
        snapshot("nonempty persistent disk record verified");
    }

    void crash() throws Exception {
        snapshot("before unacknowledged process crash");
        stopProcess(true);
        snapshot("after unacknowledged process crash; state retained");
    }

    private ProcessBuilder builder(String... arguments) {
        var command = new java.util.ArrayList<String>();
        command.add(binary);
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        // Only fixture data flows through this topology. Trace is essential to tell
        // source-read, disk-write/read and actual HTTP failures apart without adding
        // an observer sink, metrics source, or an event that could wake a stuck reader.
        builder.environment().put("VECTOR_LOG", "trace");
        builder.environment().put("VECTOR_LOG_COLOR", "false");
        return builder;
    }

    private String snapshot(String stage) throws Exception {
        String runtimeLogs = logs == null ? "" : nativeRuntime ? read(logs)
                : container == null ? "" : checkedExec("sh", "-c", "cat /fixture/vector-" + generation + ".log 2>/dev/null || true");
        String diskState;
        if (nativeRuntime) {
            try (var files = Files.walk(directory.resolve("state"))) {
                diskState = files.filter(Files::isRegularFile).map(path -> directory.relativize(path) + " bytes=" + size(path))
                        .sorted().collect(java.util.stream.Collectors.joining("\n"));
            }
        } else {
            diskState = container == null ? "not started" : checkedExec("sh", "-c", "find /state -type f -exec ls -ln {} ';'");
        }
        String diagnosis = "stage=" + stage + " generation=" + generation + "\n"
                + "sourceReadObserved=" + runtimeLogs.contains("Read bytes.") + "\n"
                + "diskWriteObserved=" + runtimeLogs.contains("Wrote record.") + "\n"
                + "diskReadObserved=" + runtimeLogs.contains("Read record.") + "\n"
                + "httpAttempts=" + received.size() + " http503=" + received.stream().filter(a -> a.status() == 503).count()
                + " http200=" + acceptedBodies().size() + "\n" + diskState + "\n"
                + "Evidence: " + evidence.toAbsolutePath() + "\n";
        if (generation > 0) Files.writeString(evidence.resolve("vector-" + generation + ".log"), runtimeLogs);
        String httpAttempts = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(received);
        Files.writeString(evidence.resolve("http-attempts.json"), httpAttempts);
        if (stage.startsWith("FAILED:")) {
            // Teardown can wake a stuck reader. Keep the deadline-time evidence
            // separately so later shutdown traffic cannot hide the actual failure.
            Files.writeString(evidence.resolve("failure-vector-" + generation + ".log"), runtimeLogs);
            Files.writeString(evidence.resolve("failure-http-attempts.json"), httpAttempts);
            Files.writeString(evidence.resolve("failure-state.log"), diagnosis);
        }
        Files.writeString(evidence.resolve("states.log"), Instant.now() + " " + diagnosis + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return diagnosis + runtimeLogs;
    }

    private String snapshotUnchecked(String stage) {
        try { return snapshot(stage); }
        catch (Exception error) { return "Evidence collection failed: " + error; }
    }

    private static void awaitCommand(Process command, int seconds, Path output, String description) throws Exception {
        try {
            assertTrue(command.waitFor(seconds, TimeUnit.SECONDS),
                    () -> description + " did not finish within " + seconds + " seconds\n" + read(output));
            assertEquals(0, command.exitValue(), () -> description + " failed\n" + read(output));
        } finally {
            if (command.isAlive()) {
                command.destroyForcibly();
                assertTrue(command.waitFor(5, TimeUnit.SECONDS), description + " could not be force-terminated");
            }
        }
    }

    private String checkedExec(String... command) throws Exception {
        var result = container.execInContainer(command);
        assertEquals(0, result.getExitCode(), () -> "Fixture command failed: " + String.join(" ", command)
                + "\n" + result.getStdout() + result.getStderr());
        return result.getStdout();
    }

    private void stopProcess(boolean crash) throws Exception {
        if (nativeRuntime) {
            if (process != null && process.isAlive()) {
                if (crash) process.destroyForcibly(); else process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Vector could not be force-terminated");
                }
            }
        } else if (container != null) {
            checkedExec("sh", "-c", "if test -f /fixture/vector.pid; then kill -" + (crash ? "KILL" : "TERM")
                    + " $(cat /fixture/vector.pid) 2>/dev/null || true; fi");
            // A zombie is already terminated; do not wait indefinitely for PID 1
            // in the fixture container to reap an orphaned exec child.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                String state = checkedExec("sh", "-c", "cat /proc/$(cat /fixture/vector.pid)/stat 2>/dev/null || true");
                if (state.isBlank() || state.matches("(?s)^\\d+ \\(.*\\) Z .*")) return;
                Thread.sleep(100);
            }
            checkedExec("sh", "-c", "kill -KILL $(cat /fixture/vector.pid) 2>/dev/null || true");
        }
    }

    @Override public void close() throws Exception {
        try {
            try { snapshot("closing"); }
            finally { stopProcess(false); }
            snapshot("stopped");
        } finally {
            if (container != null) container.close();
            server.stop(0);
        }
    }

    private static String toml(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static long size(Path path) {
        try { return Files.size(path); }
        catch (IOException error) { return -1; }
    }
    private static String read(Path path) {
        try { return Files.exists(path) ? Files.readString(path) : ""; }
        catch (IOException error) { return error.toString(); }
    }
}
