package com.socp.search.config.persistence.entity;

import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable tenant-scoped copy of one raw record that could not be parsed. */
@Entity
@Table(name = "t_ingest_parse_failure", uniqueConstraints = @UniqueConstraint(
        name = "uq_ingest_parse_failure_key", columnNames = {"tenant_id", "failure_key"}))
public class IngestParseFailureEntity extends BaseEntity {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "failure_key", nullable = false, length = 255)
    private String failureKey;
    @Column(name = "collector_id", nullable = false, length = 255)
    private String collectorId;
    @Column(name = "raw_payload", nullable = false, columnDefinition = "TEXT")
    private String rawPayload;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "parser_version", nullable = false, length = 255)
    private String parserVersion;
    @Column(name = "failure_reason", nullable = false, length = 1024)
    private String failureReason;
    @Column(name = "replay_status", nullable = false, length = 16)
    private String replayStatus;
    @Column(name = "replay_attempts", nullable = false)
    private int replayAttempts;
    @Column(name = "replayed_event_id", length = 255)
    private String replayedEventId;
    @Column(name = "replayed_at")
    private Instant replayedAt;
    @Column(name = "last_error", length = 1024)
    private String lastError;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFailureKey() { return failureKey; }
    public void setFailureKey(String failureKey) { this.failureKey = failureKey; }
    public String getCollectorId() { return collectorId; }
    public void setCollectorId(String collectorId) { this.collectorId = collectorId; }
    public String getRawPayload() { return rawPayload; }
    public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getReplayStatus() { return replayStatus; }
    public void setReplayStatus(String replayStatus) { this.replayStatus = replayStatus; }
    public int getReplayAttempts() { return replayAttempts; }
    public void setReplayAttempts(int replayAttempts) { this.replayAttempts = replayAttempts; }
    public String getReplayedEventId() { return replayedEventId; }
    public void setReplayedEventId(String replayedEventId) { this.replayedEventId = replayedEventId; }
    public Instant getReplayedAt() { return replayedAt; }
    public void setReplayedAt(Instant replayedAt) { this.replayedAt = replayedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
