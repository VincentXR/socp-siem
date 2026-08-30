package com.socp.threat.web.service;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.threat.web.domain.Ioc;
import com.socp.threat.web.persistence.entity.TaxiiCheckpointEntity;
import com.socp.threat.web.persistence.repository.TaxiiCheckpointRepository;
import com.socp.threat.web.persistence.store.IocStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaxiiSyncServiceTest {
    @Mock
    private IocStore store;
    @Mock
    private TaxiiCheckpointRepository checkpoints;
    private HttpServer server;

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        if (server != null) server.stop(0);
    }

    @Test
    void importsIndicatorsAndPersistsTenantCheckpointAfterPages() throws Exception {
        TenantContext.set("tenant-a");
        server = server("{\"objects\":[{\"type\":\"indicator\",\"id\":\"indicator--1\","
                + "\"pattern\":\"[ipv4-addr:value = '192.0.2.1']\",\"valid_from\":\"2026-01-01T00:00:00Z\"}]}" );
        given(checkpoints.findByTenantIdAndFeed("tenant-a", "feed-a")).willReturn(Optional.empty());
        given(checkpoints.save(any(TaxiiCheckpointEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        var result = new TaxiiSyncService(store, checkpoints).sync("feed-a", endpoint(), null, true);

        assertThat(result).containsEntry("tenant", "tenant-a").containsEntry("imported", 1);
        ArgumentCaptor<TaxiiCheckpointEntity> captor = ArgumentCaptor.forClass(TaxiiCheckpointEntity.class);
        verify(checkpoints).save(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(captor.getValue().getLastPage()).isEqualTo(1);
        verify(store).add(any(Ioc.class));
    }

    @Test
    void recordsSyncFailureWithoutAdvancingSuccessfulTimestamp() throws Exception {
        TenantContext.set("tenant-a");
        server = server("not-json");
        TaxiiCheckpointEntity existing = new TaxiiCheckpointEntity();
        existing.setCheckpointId("tenant-a|feed-a");
        existing.setTenantId("tenant-a");
        existing.setFeed("feed-a");
        existing.setLastPage(4);
        given(checkpoints.findByTenantIdAndFeed("tenant-a", "feed-a")).willReturn(Optional.of(existing));
        given(checkpoints.save(any(TaxiiCheckpointEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> new TaxiiSyncService(store, checkpoints)
                .sync("feed-a", endpoint(), null, true))
                .isInstanceOf(RuntimeException.class);

        assertThat(existing.getLastSyncedAt()).isEqualTo(java.time.Instant.EPOCH);
        assertThat(existing.getLastError()).contains("invalid TAXII response");
        verify(checkpoints).save(existing);
    }

    @Test
    void rejectsUnsafeFeedNamesAndMissingCollection() {
        TenantContext.set("tenant-a");
        TaxiiSyncService service = new TaxiiSyncService(store, checkpoints);
        assertThatThrownBy(() -> service.sync("feed|injection", URI.create("https://taxii.example/collection"), null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sync("feed-a", null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/collection");
    }

    private static HttpServer server(String body) throws Exception {
        HttpServer result = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        result.createContext("/collection", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        result.start();
        return result;
    }
}
