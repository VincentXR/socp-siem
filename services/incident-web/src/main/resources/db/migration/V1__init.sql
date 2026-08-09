-- incident-web 初始 schema（对应架构 §IR-4 case 库）
-- 约定同 alert-web：VARCHAR 主键、Instant → TIMESTAMP(6) WITH TIME ZONE、IF NOT EXISTS 保数据。

CREATE TABLE IF NOT EXISTS t_case (
    -- id 形如 CASE-<epochMilli>（见 Case.open），非 UUID
    id         VARCHAR(255) NOT NULL,
    title      VARCHAR(255),
    entity     VARCHAR(255),
    severity   VARCHAR(255),
    status     VARCHAR(255),
    -- 嵌套集合以 JSON 文本列落库，避免切片阶段引入关联表
    rule_ids   VARCHAR(4000),
    alarm_ids  VARCHAR(4000),
    timeline   VARCHAR(16000),
    assignee   VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    -- CaseEntity 未继承 BaseEntity（createdAt/updatedAt 会重复映射），
    -- 租户列在实体里单独声明 + @PrePersist 注入，语义与 BaseEntity 一致
    tenant_id  VARCHAR(255),
    CONSTRAINT pk_t_case PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_t_case_tenant_status ON t_case (tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_t_case_assignee ON t_case (assignee);
CREATE INDEX IF NOT EXISTS idx_t_case_entity ON t_case (entity);
