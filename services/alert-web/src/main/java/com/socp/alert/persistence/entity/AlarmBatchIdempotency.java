package com.socp.alert.persistence.entity;

import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Durable response cache for the alarm batch command. */
@Entity
@Table(name = "t_alarm_batch_idempotency", uniqueConstraints = @UniqueConstraint(
        name = "uq_alarm_batch_idempotency_tenant_key",
        columnNames = {"tenant_id", "idempotency_key"}))
public class AlarmBatchIdempotency extends BaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_json", nullable = false, columnDefinition = "TEXT")
    private String responseJson;

    public AlarmBatchIdempotency() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }
}
