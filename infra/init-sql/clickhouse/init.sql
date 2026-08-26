-- ClickHouse 初始化（SFMP13 报表聚合，§8.1）：4 个库 + 预聚合表示例
CREATE DATABASE IF NOT EXISTS report;
CREATE DATABASE IF NOT EXISTS alert_agg;
CREATE DATABASE IF NOT EXISTS detect_agg;
CREATE DATABASE IF NOT EXISTS hips_agg;

-- REPORT 报表：按天/按租户的告警与事件聚合
CREATE TABLE IF NOT EXISTS report.report_daily
(
    tenant_id String,
    day Date,
    alarm_total UInt64,
    alarm_critical UInt64,
    event_total UInt64,
    updated_at DateTime DEFAULT now()
)
ENGINE = MergeTree()
ORDER BY (tenant_id, day);

-- ALERT 预聚合：按规则/级别的告警计数（看板下钻加速）
CREATE TABLE IF NOT EXISTS alert_agg.alarm_by_rule
(
    tenant_id String,
    rule_id String,
    rule_name String,
    severity String,
    cnt UInt64,
    ts DateTime DEFAULT now()
)
ENGINE = AggregatingMergeTree()
ORDER BY (tenant_id, rule_id, severity);

-- ALERT 告警明细（2026-08-08 接线新增）：REPORT 报表 SQL 聚合的实时数据源
CREATE TABLE IF NOT EXISTS alert_agg.alarm_detail
(
    tenant_id String,
    alarm_id String,
    ts DateTime64(3),
    severity LowCardinality(String),
    rule_id String,
    rule_name String,
    entity String,
    -- Deterministic version makes retries converge during ReplacingMergeTree merges.
    -- The application deliberately keeps the same version for the same alarm.
    row_version UInt64 DEFAULT 1
)
ENGINE = ReplacingMergeTree(row_version)
PARTITION BY toYYYYMM(ts)
ORDER BY (tenant_id, alarm_id);

-- Existing installations created before alarm delivery became at-least-once
-- need the stable alarm key as well. Reports use it to collapse redeliveries.
ALTER TABLE alert_agg.alarm_detail
    ADD COLUMN IF NOT EXISTS alarm_id String AFTER tenant_id;

ALTER TABLE alert_agg.alarm_detail
    ADD COLUMN IF NOT EXISTS row_version UInt64 DEFAULT 1;

-- DETECT 聚合：5 分钟窗口匹配结果
CREATE TABLE IF NOT EXISTS detect_agg.window_match
(
    tenant_id String,
    window_start DateTime,
    rule_id String,
    matched UInt64
)
ENGINE = MergeTree()
ORDER BY (tenant_id, window_start, rule_id);

-- HIPS 聚合：端点事件统计
CREATE TABLE IF NOT EXISTS hips_agg.endpoint_event
(
    tenant_id String,
    host String,
    event_type String,
    cnt UInt64,
    ts DateTime DEFAULT now()
)
ENGINE = MergeTree()
ORDER BY (tenant_id, host, event_type);
