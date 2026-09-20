# SOCP 副作用幂等契约

本文件是发布和 Chaos 判定使用的语义契约。SOCP 的 Kafka、HTTP connector 和
ClickHouse 写入均采用至少一次传输；“没有重复副作用”必须按下面的业务键和
可查询收据证明，而不是把一次网络成功误认为 exactly-once。

| 副作用 | 业务幂等键 | 重复请求/并发请求 | 远端成功、本地未确认 | replay / DEAD | 证明方式 |
|---|---|---|---|---|---|
| Alert `t_alarm` | `(tenant_id, source_alert_id)`，`source_alert_id` 自 V20 起 NOT NULL | PostgreSQL 唯一约束；首写为准：命中同键直接返回既有事实，异内容载荷不合并、不返回 409（刻意区别于 SEARCH ingest 的内容冲突 409 语义，因告警含每次投递都会变化的运行时元数据，尚无排除它们的 canonical 指纹）；无键手工写入派生 `manual:<uuid>` | 重试命中同一 `source_alert_id`，不新建告警 | Detection outbox 可重放；Alert Web 只接收同一事实 | `count(*)` 与 `count(distinct tenant_id, source_alert_id)` |
| 告警处置 note（单条与批处置） | 单条：调用方 `Idempotency-Key`；批处置：`batch:<sha256(actor|status|assignee|reason)>`，actor 取认证主体 | 行级 note-key 账本 set-once（每行上限 2048，FIFO 驱逐）；同参重放不重复落 note；状态/指派为绝对值写入，重复应用是 no-op | 写行成功即视为受理；账本满驱逐最旧键后极远期同键重放理论上可再落一条（运维量级假设） | 无 replay 语义；DEAD 不适用 | note 的 `batch:`/前缀键可查询；`@AuditOperation` 留独立调用痕迹 |
| Detection event journal | `(tenant_id, event_id)` | 事件 claim 状态机，重复事件不再次评估 | Kafka offset 保持未提交，恢复后从 journal/outbox 重放 | terminal DEAD 只能由 DLQ/人工处理 | journal `PENDING=0`、Kafka lag=0、DLQ 有记录 |
| Alert delivery outbox | `(tenant_id, alarm_id, destination)` | 数据库唯一约束 + 原子 claim；并发 worker 只有一个 PROCESSING | 收据未确认则回到 PENDING/恢复 stale 后重试 | DEAD 不自动重放，必须显式 requeue | 各目标一条 delivery，状态和 attempts 可审计 |
| Incident | `(tenant_id, alarm_id)` | `t_alarm_case_link` 唯一约束；同一告警返回已有 case | 重试查询 link，不追加重复告警时间线 | DEAD/人工补偿由 Incident 运维负责 | link 行数=1，case `alarmIds` 只含一次 |
| Notify | `(tenant_id, alarm_id, channel_id)` | 稳定 receipt ID + 唯一约束 | 远端不可确认时允许 connector 再投递；收据写入后重试返回 cached | DEAD 需人工重发；不可逆通知不由通用 HTTP client 自动重试 | receipt 唯一键、dispatch log 与 connector response |
| SOAR | `(tenant_id, alarm_id)`，动作键为 `(playbook, alarm, actionIndex)` | evaluation receipt 和动作幂等键 | PROCESSING 超时后才可恢复；已完成返回 cached | DEAD/失败由分析员复核 | evaluation receipt、action execution ID |
| SOAR schedule | `(tenant_id, playbook_id, scheduled_for)` | 数据库唯一 claim；多实例只有一个执行者 | claim 成功后使用稳定 schedule event/action key | FAILED 保留审计，不自动重放不可逆动作 | schedule run 状态、固定时区与稳定 execution context |
| ClickHouse alarm detail | `(tenant_id, alarm_id)` | 稳定 `alarm_id` + dedup token；新表 `ReplacingMergeTree` | 允许物理重复，逻辑查询必须去重 | delivery outbox 负责重试，报表不按物理行计数 | `uniqExact(tenant_id, alarm_id)`；物理行数仅诊断 |
| SEARCH ingest event | `(tenant_id, event_id)` | `t_search_event` 与 ingestion outbox 唯一约束；相同内容重试返回 acknowledged/duplicates | 事务提交后重试只补缺失 Outbox，不重复写事件 | 数据库失败返回 503；仅重试未提交批次；超过事件保留窗口后的重放不再承诺由 PG 身份表去重 | `payload_fingerprint`、唯一索引、`created/duplicates/acknowledged` 响应 |

## 约束

### Alert `t_alarm` 幂等键非空

`t_alarm.source_alert_id` 是告警幂等键，`(tenant_id, source_alert_id)` 唯一索引只在两列
都非空时才生效（PostgreSQL 与 H2 `MODE=PostgreSQL` 默认 NULLS DISTINCT，NULL 彼此永不冲
突）。V20 先把历史 NULL 行回填为 `legacy:<id>`（逐行确定、互不合并），再对该列
`SET NOT NULL`，沿用本仓 `SET NOT NULL` 作为 fail-closed 数据校验的既有范式；因此生产
schema 上该列恒非空，与 `ContainerIdempotencyContractTest` 的建表断言对齐。

写入侧语义：

- Detection outbox 经 `AlertClient` 的单条 `POST /api/alarms` 恒定携带 `sourceAlertId`，
  重放命中同一 `(tenant_id, source_alert_id)`，不新建告警。
- 授权分析员走单条 POST 且省略 `sourceAlertId` 时，`AlarmService` 派生 `manual:<uuid>`
  作为非空、无冲突身份以保证唯一约束成立；它每次调用生成新 UUID，故这类手工无键告警在
  跨调用层面**不**去重。需要幂等的手工写入必须显式携带稳定 `sourceAlertId`。

### ClickHouse alarm detail version semantics

`alert_agg.alarm_detail` intentionally uses `ReplacingMergeTree(row_version)`
with `row_version = 1` for every immutable alarm fact. The version is not an
update sequence: retries of the same `(tenant_id, alarm_id)` must converge to
the same logical fact, while physical duplicate rows may remain until a merge.
All report and verification queries therefore use
`uniqExact(tenant_id, alarm_id)` (or an equivalent grouped logical key); raw
physical row counts are diagnostic only.

* `OpenSearch` 保存原始事件，是 Event → OpenSearch 的幂等边界（确定性
  `_id`）；当前没有 Alert → OpenSearch 事实链路，不为对称性新增一条链路。
* `DEAD` 不是成功，也不是静默丢弃。它必须出现在告警、指标和结构化 Chaos
  报告中，并由人工 replay 或补偿流程明确接管。
* `Notify` 的邮件、Webhook 和 SOAR 的隔离/封禁属于可能不可逆的副作用。默认
  不扩大 client 重试次数；SOAR 调查建议默认只生成 `REQUIRES_HUMAN_APPROVAL`
  动作，不直接执行。

## SEARCH ingest 身份与批次

`POST /search-config/api/v1/ingest` 接收 NDJSON。事件身份按以下顺序确定：

1. 采集端提供的 `eventId` / `event.id`；
2. 可验证的采集位置（Kafka topic/partition/offset、文件路径/offset、批次号/行号）；
3. 请求的 `Idempotency-Key` 加行号和内容指纹；
4. 没有上述信息时才生成随机 ID，因此同一正文的两条真实日志不会被正文哈希误合并。

`Idempotency-Key` 只稳定请求重试，不覆盖采集端显式身份。事件落库和 Kafka
Outbox 在同一事务中提交；同一租户复用 `eventId` 且内容不同返回 HTTP 409，
而相同内容只计为 `duplicates`，不会产生新的事件或 Outbox。批次按 200 条提交，
后续事务失败返回 HTTP 503，客户端只应重试尚未提交的批次；带 key 的完整重试
也会由 `(tenant_id,event_id)` 唯一约束吸收已提交部分。

PostgreSQL 中的 `t_search_event` 是有界的幂等/降级副本，不是长期检索权威。默认按
`created_at` 保留 30 天并分批清理；存在 PENDING / PROCESSING / DEAD ingestion outbox
的事件不会被清理。生产环境必须把 `SOCP_SEARCH_EVENT_RETENTION_MS` 设置为不短于采集端
最大重试窗口和 Kafka 可重放窗口，否则超过该边界后再次出现同一 `eventId` 会被视为新的
持久化事实。长期检索历史由 OpenSearch 的索引保留策略负责。

内容指纹使用 canonical payload 计算，JSON 字段顺序变化不改变身份。`ingested_at`
和 `fields.event_time_generated` 是归一化阶段的运行时元数据，不参与指纹；当采集端
没有提供有效事件时间时，归一化器会显式标记后生成事件时间，避免同一请求重试因当前
时间不同而误报 409。Outbox 载荷无法解析时按冲突处理（fail closed），不会把损坏的
发布意图当作安全重试确认。
