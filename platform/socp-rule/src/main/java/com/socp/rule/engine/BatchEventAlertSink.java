package com.socp.rule.engine;

import java.util.List;

/** All entries commit atomically, or publication throws without durable effects. */
public interface BatchEventAlertSink extends EventAlertSink {
    record Entry(DetectionResult result, Runnable guard) { }
    boolean supportsAtomicBatch();
    void publishBatch(List<Entry> entries);
}
