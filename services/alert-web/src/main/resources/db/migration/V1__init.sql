-- alert-web 初始 schema（对应架构 §8.2 alert 库）
--
-- 由 Flyway 接管（spring.jpa.hibernate.ddl-auto=none）。
-- 兼容性约定（H2 2.x MODE=PostgreSQL 与 PostgreSQL 16+ 共用同一份 DDL）：
--   * 主键/短文本一律 VARCHAR(n)，不用 CHAR(36)：Alarm 的 id 是 UUID(36)，
--     但 CHAR 在 PG 下会右填充空格，跨库比较容易出坑，统一用变长类型；
--   * java.time.Instant 在 Hibernate 6 里映射为 TIMESTAMP(6) WITH TIME ZONE，
--     H2 与 PG 都原生支持该写法，写成裸 TIMESTAMP 在 PG 上会丢时区；
--   * 全部 CREATE ... IF NOT EXISTS：本地 ~/.socp/alert.mv.db 里已有
--     ddl-auto=update 建好的表和数据，配合 baseline-on-migrate=true 不会清库。

CREATE TABLE IF NOT EXISTS t_alarm (
    id          VARCHAR(255) NOT NULL,
    rule_id     VARCHAR(255),
    rule_name   VARCHAR(255),
    -- severity 用 VARCHAR 而非 H2 的 ENUM：ENUM 在 H2 与 PG 间语法/校验不一致，
    -- 且 Java 枚举可随时增删取值，VARCHAR 迁移成本最低（见架构 §8.2 枚举约定）
    severity    VARCHAR(255),
    message     VARCHAR(1024),
    entity      VARCHAR(255),
    mitre       VARCHAR(32),
    ti_hits     VARCHAR(1024),
    risk_score  INTEGER,
    risk_level  VARCHAR(16),
    source_alert_id VARCHAR(255),
    status      VARCHAR(255),
    occurred_at TIMESTAMP(6) WITH TIME ZONE,
    -- 以下三列来自 socp-data 的 BaseEntity（@MappedSuperclass）
    tenant_id   VARCHAR(255),
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_t_alarm PRIMARY KEY (id)
);

-- 告警列表默认按发生时间倒序翻页，且几乎所有查询都带租户过滤
CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_occurred ON t_alarm (tenant_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_t_alarm_status ON t_alarm (status);
CREATE INDEX IF NOT EXISTS idx_t_alarm_entity ON t_alarm (entity);
CREATE INDEX IF NOT EXISTS idx_t_alarm_rule_id ON t_alarm (rule_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_alarm_tenant_source_alert
    ON t_alarm (tenant_id, source_alert_id);
