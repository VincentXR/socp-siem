package com.socp.detect.web.api.response;
/** Stable response contract for a single local Detection ingest request. */
public record DetectionIngestResponse(boolean accepted, Object queueLoad, String error) {
}
