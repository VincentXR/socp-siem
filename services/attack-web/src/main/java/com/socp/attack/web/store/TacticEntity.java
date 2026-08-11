package com.socp.attack.web.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ATT&CK 战术持久化实体（t_tactic）。 */
@Entity
@Table(name = "t_tactic")
public class TacticEntity {
    @Id @Column(length = 16) private String id;
    @Column(nullable = false, length = 128) private String name;
    @Column(name = "sort", nullable = false) private int sort;

    public TacticEntity() {}
    public TacticEntity(String id, String name, int sort) { this.id = id; this.name = name; this.sort = sort; }
    public String getId() { return id; }
    public String getName() { return name; }
    public int getSort() { return sort; }
}
