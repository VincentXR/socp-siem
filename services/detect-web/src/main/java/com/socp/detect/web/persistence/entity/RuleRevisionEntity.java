package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Immutable entry in a rule's spec version chain (Flyway V22, table
 * {@code t_rule_revision}).
 *
 * <p>One row is appended, in the same transaction as the owning {@code t_rule}
 * mutation, for every add/edit/activate/restore/delete. The chain is the source
 * for {@code GET /rules/{id}/revisions} and the non-destructive rollback that
 * feeds {@code POST /rules/{id}/revisions/{rev}/restore}. Rows are never updated
 * or deleted, so {@link #isNew()} is always true and {@code save} always
 * inserts.</p>
 */
@Entity
@Table(name = "t_rule_revision")
public class RuleRevisionEntity implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "rule_id", length = 128, nullable = false)
    private String ruleId;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "spec", columnDefinition = "TEXT", nullable = false)
    private String spec;

    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "source", length = 32, nullable = false)
    private String source;

    @Column(name = "changed_by", length = 128)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public RuleRevisionEntity() {
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
}
