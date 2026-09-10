package com.socp.attack.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "t_technique_note")
public class TechniqueNoteEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "tenant_id", nullable = false, length = 128) private String tenantId;
    @Column(name = "technique_id", nullable = false, length = 16) private String techniqueId;
    @Column(nullable = false, length = 4000) private String note = "";
    @Version private Long version;
    public TechniqueNoteEntity() { }
    public TechniqueNoteEntity(String tenant, String technique) {
        id = java.util.UUID.nameUUIDFromBytes((tenant.length() + ":" + tenant + technique).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        tenantId = tenant; techniqueId = technique;
    }
    public String getNote() { return note; }
    public void setNote(String value) { note = value; }
}
