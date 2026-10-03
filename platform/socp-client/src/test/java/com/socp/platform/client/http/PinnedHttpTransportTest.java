package com.socp.platform.client.http;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinnedHttpTransportTest {
    @TempDir Path temp;
    @Test void usesPinnedSocketAddressWithoutAsyncDnsAndBoundsStreamingBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> host = new AtomicReference<>();
        server.createContext("/", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write("x".repeat(2048).getBytes()); }
        }); server.start();
        try (var pinned = PinnedEndpoint.resolved("dns-not-in-global-resolver.invalid", new InetAddress[] { InetAddress.getByName("127.0.0.1") })) {
            URI uri = URI.create("http://dns-not-in-global-resolver.invalid:" + server.getAddress().getPort());
            var response = new PinnedHttpTransport().send("GET", uri, null, null, Map.of(), pinned, 500, 1500, 4096);
            assertThat(response.status()).isEqualTo(200);
            assertThat(host.get()).startsWith("dns-not-in-global-resolver.invalid:");
            assertThatThrownBy(() -> new PinnedHttpTransport().send("GET", uri, null, null, Map.of(), pinned, 500, 1500, 1024))
                    .isInstanceOf(ResponseBodyTooLargeException.class);
        } finally { server.stop(0); }
    }
    @Test void interruptionCancelsInflightRequestAndPreservesCallerControl() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        server.createContext("/", exchange -> { arrived.countDown(); try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } finally { exchange.close(); } });
        server.start();
        AtomicReference<Throwable> result = new AtomicReference<>();
        var pinned = PinnedEndpoint.resolved("cancel.invalid", new InetAddress[] { InetAddress.getByName("127.0.0.1") });
        Thread caller = new Thread(() -> { try {
            new PinnedHttpTransport().send("GET", URI.create("http://cancel.invalid:" + server.getAddress().getPort()), null,
                    null, Map.of(), pinned, 500, 30_000, 1024);
        } catch (Throwable failure) { result.set(failure); } });
        try {
            caller.start(); assertThat(arrived.await(2, TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(1500);
            assertThat(caller.isAlive()).isFalse(); assertThat(result.get()).isInstanceOf(InterruptedException.class);
        } finally { release.countDown(); caller.interrupt(); server.stop(0); pinned.close(); }
    }
    @Test void limitsDecompressedGzipBytes() throws Exception {
        var encoded = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(encoded)) { gzip.write("x".repeat(20_000).getBytes()); }
        assertThat(encoded.size()).isLessThan(1024);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, encoded.size());
            try (var output = exchange.getResponseBody()) { output.write(encoded.toByteArray()); }
        }); server.start();
        try (var pinned = PinnedEndpoint.resolved("gzip.invalid", new InetAddress[] { InetAddress.getByName("127.0.0.1") })) {
            assertThatThrownBy(() -> new PinnedHttpTransport().send("GET",
                    URI.create("http://gzip.invalid:" + server.getAddress().getPort()), null, null,
                    Map.of(), pinned, 500, 1500, 1024)).isInstanceOf(ResponseBodyTooLargeException.class);
        } finally { server.stop(0); }
    }
    @Test void totalDeadlineBoundsAContinuouslyTricklingBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < 50; i++) {
                    output.write('x'); output.flush();
                    try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                }
            }
        }); server.start();
        try (var pinned = PinnedEndpoint.resolved("slow.invalid", new InetAddress[] { InetAddress.getByName("127.0.0.1") })) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> new PinnedHttpTransport().send("GET", URI.create("http://slow.invalid:" + server.getAddress().getPort()),
                    null, null, Map.of(), pinned, 500, 250, 1024)).isInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
        } finally { server.stop(0); }
    }

    @Test void tlsChecksOriginalHostnameWithPinnedAddress() throws Exception {
        Path keyStoreFile = temp.resolve("fixture.p12");
        Process generation = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", keyStoreFile.toString(),
                "-storepass", "fixture-password", "-keypass", "fixture-password", "-dname", "CN=allowed.invalid",
                "-ext", "SAN=dns:allowed.invalid", "-validity", "2", "-noprompt").redirectErrorStream(true).start();
        assertThat(generation.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(generation.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStoreFile)) { keys.load(input, "fixture-password".toCharArray()); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "fixture-password".toCharArray());
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(200, 2); try (var output = exchange.getResponseBody()) { output.write("ok".getBytes()); } });
        server.start();
        try {
            var transport = new PinnedHttpTransport(tls);
            var pinned = PinnedEndpoint.resolved("allowed.invalid", new InetAddress[] { InetAddress.getByName("127.0.0.1") });
            assertThat(transport.send("GET", URI.create("https://allowed.invalid:" + server.getAddress().getPort()), null,
                    null, Map.of(), pinned, 500, 2000, 1024).status()).isEqualTo(200);
            var wrong = PinnedEndpoint.resolved("wrong.invalid", pinned.addresses());
            assertThatThrownBy(() -> transport.send("GET", URI.create("https://wrong.invalid:" + server.getAddress().getPort()), null,
                    null, Map.of(), wrong, 500, 2000, 1024)).isInstanceOf(javax.net.ssl.SSLException.class);
        } finally { server.stop(0); }
    }
}
