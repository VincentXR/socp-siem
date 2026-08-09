-- search-config 初始 schema（对应架构 §GLSP6 search 库）
-- 约定同 alert-web：VARCHAR 主键、Instant → TIMESTAMP(6) WITH TIME ZONE、IF NOT EXISTS 保数据。

CREATE TABLE IF NOT EXISTS t_log_source (
    id              VARCHAR(255) NOT NULL,
    name            VARCHAR(255),
    -- 列名不用 type：避开保留字风险，且与 LogSourceEntity 的 @Column(name="src_type") 对齐
    src_type        VARCHAR(255),
    format          VARCHAR(255),
    path            VARCHAR(255),
    address         VARCHAR(255),
    topic           VARCHAR(255),
    env             VARCHAR(255),
    enabled         BOOLEAN NOT NULL,
    read_from       VARCHAR(255),
    multiline       VARCHAR(2000),
    sink_target_id  VARCHAR(255),
    parse_rule_ids  VARCHAR(2000),
    description     VARCHAR(1024),
    protocol        VARCHAR(255),
    charset         VARCHAR(255),
    time_field      VARCHAR(255),
    timezone        VARCHAR(255),
    tags            VARCHAR(1024),
    frequency       INTEGER,
    category_id     VARCHAR(255),
    group_id        VARCHAR(255),
    created_at      TIMESTAMP(6) WITH TIME ZONE,
    -- LogSourceEntity 未继承 BaseEntity（createdAt 会重复映射），
    -- 租户列单独声明 + @PrePersist 注入
    tenant_id       VARCHAR(255),
    CONSTRAINT pk_t_log_source PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_t_log_source_tenant ON t_log_source (tenant_id);
CREATE INDEX IF NOT EXISTS idx_t_log_source_enabled ON t_log_source (enabled);

CREATE TABLE IF NOT EXISTS t_search_event (
    id          VARCHAR(255) NOT NULL,
    -- timestamp 是事件自带的发生时间；PG 与 H2 均把 timestamp 视为非保留字，可直接做列名
    timestamp   TIMESTAMP(6) WITH TIME ZONE,
    source      VARCHAR(255),
    host        VARCHAR(255),
    severity    VARCHAR(255),
    msg         VARCHAR(2000),
    fields_json VARCHAR(4000),
    -- 以下三列来自 socp-data 的 BaseEntity（@MappedSuperclass）；
    -- created_at 是入库时间，与上面的 timestamp（日志发生时间）语义不同
    tenant_id   VARCHAR(255),
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_t_search_event PRIMARY KEY (id)
);

-- 检索页默认按时间倒序 + 租户过滤
CREATE INDEX IF NOT EXISTS idx_t_search_event_tenant_ts ON t_search_event (tenant_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_t_search_event_source ON t_search_event (source);
CREATE INDEX IF NOT EXISTS idx_t_search_event_host ON t_search_event (host);
