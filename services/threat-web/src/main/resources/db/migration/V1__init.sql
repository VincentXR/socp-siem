-- threat-web 初始 schema（对应架构 §TIM-2 threat 库）
-- 约定同 alert-web：VARCHAR 主键、Instant → TIMESTAMP(6) WITH TIME ZONE、IF NOT EXISTS 保数据。

CREATE TABLE IF NOT EXISTS t_ioc (
    -- id 由 "type:value" 拼成（见 Ioc.of），不是 UUID，必须留足长度
    id          VARCHAR(255) NOT NULL,
    type        VARCHAR(255),
    -- 列名不能叫 value：VALUE 是 H2 2.x / SQL:2016 保留字（与 IocEntity 注释一致）
    ioc_value   VARCHAR(255),
    severity    VARCHAR(255),
    source      VARCHAR(255),
    description VARCHAR(1024),
    tags        VARCHAR(1024),
    first_seen  TIMESTAMP(6) WITH TIME ZONE,
    last_seen   TIMESTAMP(6) WITH TIME ZONE,
    -- 以下三列来自 socp-data 的 BaseEntity（@MappedSuperclass）
    tenant_id   VARCHAR(255),
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_t_ioc PRIMARY KEY (id)
);

-- 情报匹配的热路径是「按类型 + 值精确查」，富化时每条告警都会打一次
CREATE INDEX IF NOT EXISTS idx_t_ioc_type_value ON t_ioc (type, ioc_value);
CREATE INDEX IF NOT EXISTS idx_t_ioc_tenant ON t_ioc (tenant_id);
CREATE INDEX IF NOT EXISTS idx_t_ioc_tenant_value ON t_ioc (tenant_id, ioc_value);
