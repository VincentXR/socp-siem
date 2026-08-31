package com.socp.search.config.infrastructure.opensearch;

import com.socp.search.config.domain.SearchEvent;

import java.util.ArrayList;
import java.util.List;

/** Per-item OpenSearch acknowledgement contract used by the Kafka indexer. */
public record BulkWriteResult(
        List<String> acknowledgedIds,
        List<Failure> retryableFailures,
        List<Failure> permanentFailures,
        long tookMs
) {
    public BulkWriteResult {
        acknowledgedIds = List.copyOf(acknowledgedIds == null ? List.of() : acknowledgedIds);
        retryableFailures = List.copyOf(retryableFailures == null ? List.of() : retryableFailures);
        permanentFailures = List.copyOf(permanentFailures == null ? List.of() : permanentFailures);
        if (tookMs < 0) tookMs = 0;
    }

    public static BulkWriteResult empty() {
        return new BulkWriteResult(List.of(), List.of(), List.of(), 0L);
    }

    public static BulkWriteResult retryAll(List<SearchEvent> events, String reasonCode,
                                           String reason, Integer status, long tookMs) {
        return failAll(events, reasonCode, reason, status, tookMs, false);
    }

    public static BulkWriteResult permanentAll(List<SearchEvent> events, String reasonCode,
                                               String reason, Integer status, long tookMs) {
        return failAll(events, reasonCode, reason, status, tookMs, true);
    }

    private static BulkWriteResult failAll(List<SearchEvent> events, String reasonCode,
                                           String reason, Integer status, long tookMs,
                                           boolean permanent) {
        List<Failure> failures = new ArrayList<>();
        List<SearchEvent> safeEvents = events == null ? List.of() : events;
        for (int i = 0; i < safeEvents.size(); i++) {
            failures.add(new Failure(i, safeEvents.get(i).eventId(), reasonCode, reason, status));
        }
        return permanent
                ? new BulkWriteResult(List.of(), List.of(), failures, tookMs)
                : new BulkWriteResult(List.of(), failures, List.of(), tookMs);
    }

    public boolean fullyAcknowledged(int expectedItems) {
        return retryableFailures.isEmpty() && permanentFailures.isEmpty()
                && acknowledgedIds.size() == expectedItems;
    }

    public record Failure(int itemIndex, String eventId, String reasonCode,
                          String reason, Integer status) {
        public Failure {
            if (itemIndex < 0) throw new IllegalArgumentException("itemIndex must not be negative");
            eventId = eventId == null ? "" : eventId;
            reasonCode = clean(reasonCode, 80, "opensearch_failure");
            reason = clean(reason, 500, reasonCode);
        }

        private static String clean(String value, int maximum, String fallback) {
            String cleaned = value == null ? "" : value.replace('\r', ' ')
                    .replace('\n', ' ').replace('\t', ' ').trim();
            if (cleaned.isBlank()) cleaned = fallback;
            return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
        }
    }
}
