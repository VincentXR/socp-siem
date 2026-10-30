package com.socp.rule;

import com.socp.rule.engine.OrderedCommitGuard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderedCommitGuardTest {
    @Test
    void batchesOnlyAdjacentCompatibleChecksInGlobalLockOrder() {
        List<String> calls = new ArrayList<>();
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        OrderedCommitGuard.runAll(List.of(
                guard("g", firstOwner, calls), guard("b", firstOwner, calls),
                guard("d", secondOwner, calls), guard("a", firstOwner, calls),
                new OrderedCommitGuard("f", () -> calls.add("legacy-f")),
                guard("c", secondOwner, calls), guard("e", firstOwner, calls)));
        assertEquals(List.of("batch-a,b", "batch-c,d", "single-e", "legacy-f", "single-g"), calls);
    }

    @Test
    void aRejectedBatchStopsBeforeLaterOwnerLocks() {
        List<String> calls = new ArrayList<>();
        Object owner = new Object();
        var rejecting = new OrderedCommitGuard.BatchCheck() {
            @Override public Object batchOwner() { return owner; }
            @Override public void run() { throw new AssertionError("batch required"); }
            @Override public void runBatch(List<OrderedCommitGuard.BatchCheck> checks) {
                assertEquals(2, checks.size());
                throw new IllegalStateException("stale epoch");
            }
        };
        assertThrows(IllegalStateException.class, () -> OrderedCommitGuard.runAll(List.of(
                new OrderedCommitGuard("a", rejecting), new OrderedCommitGuard("b", rejecting),
                new OrderedCommitGuard("c", () -> calls.add("unexpected")))));
        assertTrue(calls.isEmpty());
    }

    private OrderedCommitGuard guard(String key, Object owner, List<String> calls) {
        return new OrderedCommitGuard(key, new Check(key, owner, calls));
    }

    private record Check(String key, Object batchOwner, List<String> calls) implements OrderedCommitGuard.BatchCheck {
        @Override public void run() { calls.add("single-" + key); }
        @Override public void runBatch(List<OrderedCommitGuard.BatchCheck> checks) {
            calls.add("batch-" + checks.stream().map(check -> ((Check) check).key()).collect(java.util.stream.Collectors.joining(",")));
        }
    }
}
