package com.socp.search.config.service.port;

/**
 * Application-facing trigger for publishing committed ingestion outbox rows.
 *
 * <p>The API role may persist an outbox row without loading a worker. The
 * worker role supplies the infrastructure implementation that wakes its
 * delivery loop after a successful database transaction.</p>
 */
@FunctionalInterface
public interface IngestionPublicationTrigger {

    void triggerAsync();
}
