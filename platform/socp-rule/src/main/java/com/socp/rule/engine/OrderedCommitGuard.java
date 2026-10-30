package com.socp.rule.engine;

/** Authoritative fence with a stable database lock order for multi-event commits. */
public record OrderedCommitGuard(String lockKey, Runnable check) implements Runnable {
    public OrderedCommitGuard {
        java.util.Objects.requireNonNull(lockKey);
        java.util.Objects.requireNonNull(check);
    }
    @Override public void run() { check.run(); }

    /** Checks from one authority may share a bounded, transactional database operation. */
    public interface BatchCheck extends Runnable {
        Object batchOwner();
        void runBatch(java.util.List<BatchCheck> checks);
    }

    /**
     * Keep global lock order even when different authorities or legacy checks
     * are interleaved. Only adjacent checks from the same authority are grouped.
     */
    public static void runAll(java.util.List<OrderedCommitGuard> guards) {
        if (guards == null || guards.size() > 64) throw new IllegalArgumentException("guard batch limit is 64");
        var sorted = guards.stream().sorted(java.util.Comparator.comparing(OrderedCommitGuard::lockKey)).toList();
        for (int i = 0; i < sorted.size();) {
            Runnable check = sorted.get(i).check();
            if (!(check instanceof BatchCheck first) || first.batchOwner() == null) {
                check.run();
                i++;
                continue;
            }
            Object owner = first.batchOwner();
            var compatible = new java.util.ArrayList<BatchCheck>();
            compatible.add(first);
            int next = i + 1;
            while (next < sorted.size() && sorted.get(next).check() instanceof BatchCheck candidate
                    && candidate.batchOwner() == owner) {
                compatible.add(candidate);
                next++;
            }
            if (compatible.size() == 1) first.run();
            else first.runBatch(java.util.List.copyOf(compatible));
            i = next;
        }
    }
}
