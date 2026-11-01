package com.socp.rule.engine;

import java.util.List;

/** All entries commit atomically, or publication throws without durable effects. */
public interface BatchEventAlertSink extends EventAlertSink {
    record Entry(DetectionResult result, Runnable guard, RuleExecutionScope scope) {
        public Entry(DetectionResult result, Runnable guard) {
            this(result, guard, RuleExecutionScope.NOOP);
        }
    }
    boolean supportsAtomicBatch();

    /** Preserve per-event context and fences when adapting an explicit atomic sink. */
    default void publishBatch(List<Entry> entries) {
        publishBatch(entries.stream().map(Entry::result).toList(),
                entries.stream().map(Entry::scope).toList(), () -> {
                    for (Entry entry : entries) {
                        if (entry.guard() != null) {
                            try (RuleExecutionScope.Scope ignored = entry.scope().open(entry.result().event())) {
                                entry.guard().run();
                            }
                        }
                    }
                });
    }

    /** Failure must roll back every result; notifications follow the commit. */
    default void publishBatch(List<DetectionResult> results, List<RuleExecutionScope> scopes,
                              Runnable durableCommitGuard) {
        throw new UnsupportedOperationException("atomic batch publication is not implemented");
    }
}
