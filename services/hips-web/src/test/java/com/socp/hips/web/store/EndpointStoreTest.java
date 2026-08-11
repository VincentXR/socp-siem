package com.socp.hips.web.store;

import com.socp.hips.web.model.Endpoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HIPS 端点注册表单测：注册 / 心跳刷新 / 注销（repository 用 mock，走内存语义）。
 */
class EndpointStoreTest {

    /** 空库启动（模拟首次运行灌种子）。 */
    private static EndpointStore freshStore() {
        EndpointRepository repo = mock(EndpointRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        EndpointStore store = new EndpointStore(repo);
        store.init();
        return store;
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
