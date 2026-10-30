package com.socp.rule.state;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StateEvidenceCacheTest {
    @Test
    void cachedEvidencePreservesEscapingTenantIdentityAndBoundedRetention() {
        var event = event("one", "tenant-a", "quote\" newline\n 中文 😀");
        Object encoded = StateSnapshotCodec.event(event);
        assertSame(encoded, StateSnapshotCodec.event(event));
        var equalCopy = event("one", "tenant-a", event.raw());
        assertEquals(event, equalCopy);
        assertNotSame(encoded, StateSnapshotCodec.event(equalCopy), "cache keys use immutable instance identity");
        assertArrayEquals(StateSnapshotCodec.write(encoded), StateSnapshotCodec.write(StateSnapshotCodec.event(equalCopy)));
        var decoded = StateSnapshotCodec.event((Object) StateSnapshotCodec.read(StateSnapshotCodec.write(encoded)));
        assertEquals(event, decoded);
        var otherTenant = event("one", "tenant-b", event.raw());
        assertNotEquals(StateSnapshotCodec.read(StateSnapshotCodec.write(encoded)),
                StateSnapshotCodec.read(StateSnapshotCodec.write(StateSnapshotCodec.event(otherTenant))));
        for (int i = 0; i < 5_000; i++) StateSnapshotCodec.event(event("evict-" + i, "tenant-a", "x".repeat(400)));
        assertTrue(StateSnapshotCodec.cachedEvents() <= 4_096);
        assertTrue(StateSnapshotCodec.cachedEventBytes() <= 8L * 1024 * 1024);
        assertArrayEquals(StateSnapshotCodec.write(encoded), StateSnapshotCodec.write(StateSnapshotCodec.event(event)));
    }
    @Test
    void merkleEvidenceMatchesPortableJsonAndUndoDetachesMutableContainers() {
        Object evidence = StateSnapshotCodec.event(event("one", "tenant-a", "x".repeat(10_000)));
        Object portable = StateSnapshotCodec.read(StateSnapshotCodec.write(evidence));
        assertArrayEquals(StateSnapshotCodec.fingerprint(portable), StateSnapshotCodec.fingerprint(evidence));
        var items = new java.util.ArrayList<>(java.util.List.of(evidence));
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("count", 1);
        long[] bits = {3L};
        state.put("bits", bits);
        state.put("evidence", items);
        Object before = StateSnapshotCodec.freeze(state);
        byte[] digest = StateSnapshotCodec.fingerprint(before);
        assertArrayEquals(StateSnapshotCodec.fingerprint(new long[] {3L}),
                StateSnapshotCodec.fingerprint(java.util.List.of(3)));
        bits[0] = 7L;
        items.clear();
        state.put("count", 2);
        assertArrayEquals(digest, StateSnapshotCodec.fingerprint(before));
        assertFalse(java.util.Arrays.equals(digest, StateSnapshotCodec.fingerprint(state)));
        assertSame(evidence, ((java.util.List<?>) ((Map<?, ?>) before).get("evidence")).getFirst());
        assertTrue(StateSnapshotCodec.retainedBytes(before) > 40_000);
        assertFalse(java.util.Arrays.equals(StateSnapshotCodec.fingerprint(Map.of("a", "bc")),
                StateSnapshotCodec.fingerprint(Map.of("ab", "c"))));
    }

    private SecurityEvent event(String id, String tenant, String raw) {
        return new SecurityEvent(id, Instant.EPOCH, "test", "host", raw, Map.of("tenant_id", tenant), Severity.INFO);
    }
}
