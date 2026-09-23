package com.socp.detect.web.persistence.entity;


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

    @Column(name = "catalog_name", nullable = false, columnDefinition = "TEXT")
    private String catalogName;
    @Column(name = "catalog_type", nullable = false, columnDefinition = "TEXT")
    private String catalogType;
    @Column(name = "catalog_status", nullable = false, length = 32)
    private String catalogStatus;
    @Column(name = "catalog_search", nullable = false, columnDefinition = "TEXT")
    private String catalogSearch;
    @Column(name = "catalog_references", nullable = false, columnDefinition = "TEXT")
    private String catalogReferences;
    @Column(name = "catalog_techniques", nullable = false, columnDefinition = "TEXT")
    private String catalogTechniques;

    public RuleEntity() {
    }

    public String getId() { return ruleId; }
    public void setId(String id) { this.ruleId = id; }
    public String getStorageId() { return storageId; }
    public void setStorageId(String storageId) { this.storageId = storageId; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) {
        this.spec = spec;
        setCatalog(com.socp.detect.web.model.RuleCatalogMetadata.from(com.socp.rule.util.Json.parseObject(spec)));
    }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    private void setCatalog(com.socp.detect.web.model.RuleCatalogMetadata catalog) {
        catalogName = catalog.name();
        catalogType = catalog.type();
        catalogStatus = catalog.status();
        catalogSearch = catalog.search();
        catalogReferences = catalog.references();
        catalogTechniques = catalog.techniques();
    }
}
