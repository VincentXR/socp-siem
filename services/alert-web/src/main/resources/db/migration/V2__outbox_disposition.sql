-- alert-web P3（2026-08-12）：Outbox 事件化 + 告警处置持久化
-- 兼容性约定同 V1：VARCHAR 不用 CHAR；TIMESTAMP(6) WITH TIME ZONE；CREATE ... IF NOT EXISTS。

-- 告警出站事件（Outbox）：与 t_alarm 同事务写入，OutboxPublisher 后台发 Kafka socp-alarm-events。
-- 下游（CK/Incident/SOAR/Notify）从 Kafka 消费 → 失败可重放，不依赖跨系统双写。
CREATE TABLE IF NOT EXISTS outbox_event (
    id           VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255),
    event_type   VARCHAR(64),
    payload      TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP(6) WITH TIME ZONE,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_outbox_event PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_event (status, created_at);

-- 告警处置（assignee/notes/状态历史）：此前纯内存 ConcurrentHashMap，重启即丢；
-- 2026-08-12 起落库持久化，与 t_alarm 分离便于保留处置历史。
CREATE TABLE IF NOT EXISTS t_alarm_disposition (
    id         VARCHAR(255) NOT NULL,
    alarm_id   VARCHAR(255) NOT NULL,
    assignee   VARCHAR(255),
    status     VARCHAR(255),
    notes      TEXT,
    tenant_id  VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_alarm_disposition PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_alarm_disp_alarm ON t_alarm_disposition (alarm_id);
