package com.socp.search.config.persistence.store;

import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SinkTargetStoreTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void limitsTenantTargetsAndAllowsReplacementAfterDeletion() {
        SinkTargetStore store = new SinkTargetStore();
        TenantContext.set("tenant-a");
        for (int index = 0; index < 128; index++) store.save(target("target-" + index));

        assertEquals(400, assertThrows(ApiException.class,
                () -> store.save(target("overflow"))).getCode());
        assertTrue(store.delete(store.list().getFirst().id()));
        store.save(target("replacement"));
        assertEquals(128, store.list().size());

        TenantContext.set("tenant-b");
        assertTrue(store.list().isEmpty());
        store.save(target("independent"));
        assertEquals(1, store.list().size());
        assertFalse(store.delete(SinkTargetStore.PLATFORM_INGEST_ID));
    }

    @Test
    void editingPreservesIdentityAndRotatesOnlyTheSelectedTenantCredential() {
        SinkTargetStore store = new SinkTargetStore();
        TenantContext.set("tenant-a");
        SinkTarget original = store.save(SinkTarget.create("a", "HTTP", "https://example.test/old", "isolated-test-token", true));
        var body = new com.socp.search.config.api.request.SinkTargetRequest("edited", "HTTP", "https://example.test/new", null, true);
        var keep = new com.socp.search.config.api.request.SinkTargetUpdateRequest(body,
                com.socp.search.config.api.request.SinkTargetUpdateRequest.CredentialAction.KEEP);
        SinkTarget updated = store.update(original.id(), keep);
        assertEquals(original.id(), updated.id());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(original.authToken(), updated.authToken());
        assertEquals("https://example.test/new", updated.uri());
        assertThrows(ApiException.class, () -> store.update(SinkTargetStore.PLATFORM_INGEST_ID, keep));
        TenantContext.set("tenant-b");
        assertThrows(ApiException.class, () -> store.update(original.id(), keep));
        TenantContext.set("tenant-a");
        var replace = new com.socp.search.config.api.request.SinkTargetUpdateRequest(body,
                com.socp.search.config.api.request.SinkTargetUpdateRequest.CredentialAction.REPLACE);
        assertThrows(ApiException.class, () -> store.update(original.id(), replace));
        var replacement = new com.socp.search.config.api.request.SinkTargetRequest(
                "rotated", "HTTP", "https://example.test/new", "isolated-rotated-token", true);
        var rotated = store.update(original.id(), new com.socp.search.config.api.request.SinkTargetUpdateRequest(
                replacement, com.socp.search.config.api.request.SinkTargetUpdateRequest.CredentialAction.REPLACE));
        assertEquals("isolated-rotated-token", rotated.authToken());
        assertEquals(original.id(), rotated.id());
        assertEquals(original.createdAt(), rotated.createdAt());
        assertEquals(1, store.list().size());
        var cleared = store.update(original.id(), new com.socp.search.config.api.request.SinkTargetUpdateRequest(body,
                com.socp.search.config.api.request.SinkTargetUpdateRequest.CredentialAction.CLEAR));
        org.junit.jupiter.api.Assertions.assertNull(cleared.authToken());
    }

    private static SinkTarget target(String name) {
        return SinkTarget.create(name, "HTTP", "https://example.test/ingest", null, true);
    }
}
