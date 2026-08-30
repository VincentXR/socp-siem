package com.socp.attack.web.persistence.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ATT&CK 技术条目持久化实体（t_technique）。 */
@Entity
@Table(name = "t_technique")
public class TechniqueEntity {
    @Id @Column(length = 16) private String id;
    @Column(nullable = false, length = 256) private String name;
    @Column(length = 16) private String tactic;
    @Column(length = 512) private String url;
    @Column(length = 1024) private String description;

    public TechniqueEntity() {}
    public TechniqueEntity(String id, String name, String tactic, String url, String description) {
        this.id = id; this.name = name; this.tactic = tactic; this.url = url; this.description = description;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getTactic() { return tactic; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public void setName(String name) { this.name = name; }
    public void setTactic(String tactic) { this.tactic = tactic; }
    public void setUrl(String url) { this.url = url; }
    public void setDescription(String description) { this.description = description; }
}
