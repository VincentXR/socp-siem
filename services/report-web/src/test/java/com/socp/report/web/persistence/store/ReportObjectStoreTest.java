package com.socp.report.web.persistence.store;

import com.socp.platform.error.exception.ApiException;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReportObjectStoreTest {
    private static final String KEY = "reports/tenant-a/20260923/daily.json";

    @Test
    void disabledStorageFailsAllArchiveOperationsExplicitly() {
        ReportObjectStore store = new ReportObjectStore(
                "http://localhost:9000", "key", "secret", "reports", false);
        assertUnavailable(() -> store.put(KEY, "{}", "application/json"));
        assertUnavailable(() -> store.list("reports/tenant-a/"));
        assertUnavailable(() -> store.presignedGet(KEY));
        assertUnavailable(() -> store.remove(KEY));
        assertThat(ReportObjectStore.today()).matches("\\d{8}");
    }

    @Test
    void failedWritesSignaturesAndDeletesNeverReturnSuccessSentinels() throws Exception {
        MinioClient client = mock(MinioClient.class);
        ReportObjectStore store = new ReportObjectStore(client, "reports");
        IOException failure = new IOException("private endpoint and credential details");
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(failure);
        when(client.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(failure);
        doThrow(failure).when(client).removeObject(any(RemoveObjectArgs.class));
        assertUnavailable(() -> store.put(KEY, "{}", "application/json"));
        assertUnavailable(() -> store.presignedGet(KEY));
        assertUnavailable(() -> store.remove(KEY));
    }

    @Test
    void listingFailureDiscardsAlreadyReadObjects() {
        MinioClient client = mock(MinioClient.class);
        ReportObjectStore store = new ReportObjectStore(client, "reports");
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(KEY);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(
                new Result<>(item), new Result<Item>(new IOException("private endpoint and credential details"))));
        assertUnavailable(() -> store.list("reports/tenant-a/", 2));
    }

    @Test
    void listingDoesNotFetchAnotherPageAfterReachingItsBound() {
        MinioClient client = mock(MinioClient.class);
        ReportObjectStore store = new ReportObjectStore(client, "reports");
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn(KEY);
        when(item.size()).thenReturn(42L);
        Iterator<Result<Item>> iterator = new Iterator<>() {
            private boolean consumed;
            public boolean hasNext() {
                if (consumed) throw new AssertionError("Unnecessary next-page request");
                return true;
            }
            public Result<Item> next() {
                consumed = true;
                return new Result<>(item);
            }
        };
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(() -> iterator);
        assertThat(store.list("reports/tenant-a/", 1)).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("key", KEY).containsEntry("size", 42L));
    }

    @Test
    void successfulEmptyListingRemainsDifferentFromUnavailableStorage() {
        MinioClient client = mock(MinioClient.class);
        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of());
        assertThat(new ReportObjectStore(client, "reports").list("reports/tenant-a/")).isEmpty();
    }

    @Test
    void realSdkTransportUsesACompatibleRuntimeClasspath() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var received = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/reports/", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("ETag", "\"fixture-etag\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                    .credentials("report-fixture", "report-fixture-secret").region("us-east-1").build();
            ReportObjectStore store = new ReportObjectStore(client, "reports");
            assertThat(store.put(KEY, "{\"total\":42}", "application/json")).isEqualTo(KEY);
            assertThat(received.get()).isEqualTo("{\"total\":42}");
        } finally {
            server.stop(0);
        }
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", 503)
                .hasMessageNotContaining("private endpoint");
    }
}
