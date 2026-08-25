package com.socp.detect.web.persistence.entity;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 检测规则持久化实体（H2/PG，Flyway 建表；spec 以 JSON 字符串存储）。 */
@Entity
@Table(name = "t_rule")
public class RuleEntity {

    @Id
    @Column(name = "id", length = 160)
    private String storageId;

    @Column(name = "rule_id", length = 64, nullable = false)
    private String ruleId;

    /** RuleSpec 完整 JSON（含 name/type/severity/match 等自由结构） */
    @Column(length = 4096, nullable = false)
    private String spec;

    @Column(length = 64)
    private String tenantId;

    public RuleEntity() {
    }

    public String getId() { return ruleId; }
    public void setId(String id) { this.ruleId = id; }
    public String getStorageId() { return storageId; }
    public void setStorageId(String storageId) { this.storageId = storageId; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
