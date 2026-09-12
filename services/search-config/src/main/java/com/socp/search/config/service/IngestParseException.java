package com.socp.search.config.service;

/**
 * Expected rejection of one event at the ingest boundary.
 *
 * <p>This remains an {@link IllegalArgumentException} for source compatibility,
 * but gives the batch pipeline a type-safe way to distinguish malformed or
 * over-budget input from a database, rules, or reference-data outage.</p>
 */
public class IngestParseException extends IllegalArgumentException {

    public IngestParseException(String message) {
        super(message);
    }
}
