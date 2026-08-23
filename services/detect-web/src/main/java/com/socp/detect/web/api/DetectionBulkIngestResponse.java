package com.socp.detect.web.api;

/** Stable response contract for NDJSON Detection ingest. */
public record DetectionBulkIngestResponse(int accepted, int rejected, Object queueLoad) {
}
