package com.socp.search.config.service;

/** A producer reused an event identity for content different from the durable copy. */
public final class IngestionIdentityConflictException extends RuntimeException {

    private final String eventId;

    public IngestionIdentityConflictException(String eventId) {
        super("event identity was reused with different content: " + eventId);
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
