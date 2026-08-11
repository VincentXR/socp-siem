package com.socp.ai.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 知识库 QA 持久化实体（t_qa）。 */
@Entity
@Table(name = "t_qa")
public class QaEntity {
    @Id @Column(length = 128) private String keyword;
    @Column(nullable = false, length = 4096) private String answer;

    public QaEntity() {}
    public QaEntity(String keyword, String answer) { this.keyword = keyword; this.answer = answer; }
    public String getKeyword() { return keyword; }
    public String getAnswer() { return answer; }
}
