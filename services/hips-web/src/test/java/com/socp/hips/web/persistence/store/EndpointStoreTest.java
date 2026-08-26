package com.socp.hips.web.persistence.store;



import com.socp.hips.web.persistence.store.*;
import com.socp.hips.web.persistence.repository.*;
import com.socp.hips.web.persistence.entity.*;
import com.socp.hips.web.domain.Endpoint;
import com.socp.platform.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HIPS 端点注册表单测：注册 / 心跳刷新 / 注销（repository 用 mock，走内存语义）。
 */
class EndpointStoreTest {

    @BeforeEach
    void setTenant() {
        TenantContext.set("default");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 空库启动（模拟首次运行灌种子）。 */
    private static EndpointStore freshStore() {
        EndpointRepository repo = mock(EndpointRepository.class);
        var rows = new ConcurrentHashMap<String, EndpointEntity>();
        when(repo.findByTenantId(anyString())).thenAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            return rows.values().stream().filter(row -> tenant.equals(row.getTenantId())).toList();
        });
        when(repo.findByTenantIdAndEndpointId(anyString(), anyString())).thenAnswer(invocation -> {
            String tenant = invocation.getArgument(0);
            String endpointId = invocation.getArgument(1);
            return rows.values().stream()
                    .filter(row -> tenant.equals(row.getTenantId()) && endpointId.equals(row.getEndpointId()))
                    .findFirst();
        });
        when(repo.save(any(EndpointEntity.class))).thenAnswer(invocation -> {
            EndpointEntity row = invocation.getArgument(0);
            rows.put(row.getStorageId(), row);
            return row;
        });
        doAnswer(invocation -> {
            EndpointEntity row = invocation.getArgument(0);
            rows.remove(row.getStorageId());
            return null;
        }).when(repo).delete(any(EndpointEntity.class));
        EndpointStore store = new EndpointStore(repo);
        store.init();
        TenantContext.set("default");
        return store;
    }

    @Test
    void endpointIdentityAndMemoryViewAreTenantScoped() {
        EndpointStore store = freshStore();
        Endpoint shared = new Endpoint("shared-id", "tenant-a-host", "10.1.0.1", "Linux",
                "1.0", "ONLINE", Instant.now());
        TenantContext.set("tenant-a");
        store.save(shared);

        TenantContext.set("tenant-b");
        assertTrue(store.list().isEmpty());
        assertNull(store.heartbeat("shared-id"));
        store.save(new Endpoint("shared-id", "tenant-b-host", "10.2.0.1", "Linux",
                "1.0", "ONLINE", Instant.now()));

        TenantContext.set("tenant-a");
        assertEquals("tenant-a-host", store.heartbeat("shared-id").hostname());
        assertEquals(1, store.list().size());
    }

    @Test
    void seedsThreeAgents() {
        EndpointStore store = freshStore();

        assertEquals(3, store.list().size());
        assertTrue(store.list().stream().allMatch(e -> "ONLINE".equals(e.status())));
        assertTrue(store.list().stream().anyMatch(e -> "db-master".equals(e.hostname())));
    }

    @Test
    void heartbeatRefreshesTimestampAndKeepsIdentity() throws Exception {
        EndpointStore store = freshStore();
        Endpoint registered = store.save(Endpoint.register("app01", "10.0.0.30", "RHEL 9", "falco-0.39"));
        Instant firstBeat = registered.lastHeartbeat();
        Thread.sleep(5);

        Endpoint beaten = store.heartbeat(registered.id());

        assertNotNull(beaten);
        assertEquals(registered.id(), beaten.id(), "心跳不应换 id");
        assertEquals("app01", beaten.hostname());
        assertEquals("ONLINE", beaten.status());
        assertTrue(beaten.lastHeartbeat().isAfter(firstBeat), "lastHeartbeat 应被刷新");
    }

    @Test
    void heartbeatOnUnknownEndpointReturnsNull() {
        assertNull(freshStore().heartbeat("not-registered"));
    }

    @Test
    void deleteIsIdempotentAfterFirstRemoval() {
        EndpointStore store = freshStore();
        Endpoint e = store.save(Endpoint.register("tmp", "10.0.0.99", "Alpine", "falco-0.39"));

        assertTrue(store.delete(e.id()));
        assertFalse(store.delete(e.id()));
        assertEquals(3, store.list().size(), "只剩种子端点");
    }
}
