# SOCP 副作用幂等契约

本文件是发布和 Chaos 判定使用的语义契约。SOCP 的 Kafka、HTTP connector 和
ClickHouse 写入均采用至少一次传输；“没有重复副作用”必须按下面的业务键和
可查询收据证明，而不是把一次网络成功误认为 exactly-once。

| 副作用 | 业务幂等键 | 重复请求/并发请求 | 远端成功、本地未确认 | replay / DEAD | 证明方式 |
|---|---|---|---|---|---|
| Alert `t_alarm` | `(tenant_id, source_alert_id)` | PostgreSQL 唯一约束；已有事实直接返回 | 重试命中同一 `source_alert_id`，不新建告警 | Detection outbox 可重放；Alert Web 只接收同一事实 | `count(*)` 与 `count(distinct tenant_id, source_alert_id)` |
| Detection event journal | `(tenant_id, event_id)` | 事件 claim 状态机，重复事件不再次评估 | Kafka offset 保持未提交，恢复后从 journal/outbox 重放 | terminal DEAD 只能由 DLQ/人工处理 | journal `PENDING=0`、Kafka lag=0、DLQ 有记录 |
| Alert delivery outbox | `(tenant_id, alarm_id, destination)` | 数据库唯一约束 + 原子 claim；并发 worker 只有一个 PROCESSING | 收据未确认则回到 PENDING/恢复 stale 后重试 | DEAD 不自动重放，必须显式 requeue | 各目标一条 delivery，状态和 attempts 可审计 |
| Incident | `(tenant_id, alarm_id)` | `t_alarm_case_link` 唯一约束；同一告警返回已有 case | 重试查询 link，不追加重复告警时间线 | DEAD/人工补偿由 Incident 运维负责 | link 行数=1，case `alarmIds` 只含一次 |
| Notify | `(tenant_id, alarm_id, channel_id)` | 稳定 receipt ID + 唯一约束 | 远端不可确认时允许 connector 再投递；收据写入后重试返回 cached | DEAD 需人工重发；不可逆通知不由通用 HTTP client 自动重试 | receipt 唯一键、dispatch log 与 connector response |
| SOAR | `(tenant_id, alarm_id)`，动作键为 `(playbook, alarm, actionIndex)` | evaluation receipt 和动作幂等键 | PROCESSING 超时后才可恢复；已完成返回 cached | DEAD/失败由分析员复核 | evaluation receipt、action execution ID |
| SOAR schedule | `(tenant_id, playbook_id, scheduled_for)` | 数据库唯一 claim；多实例只有一个执行者 | claim 成功后使用稳定 schedule event/action key | FAILED 保留审计，不自动重放不可逆动作 | schedule run 状态、固定时区与稳定 execution context |
| ClickHouse alarm detail | `(tenant_id, alarm_id)` | 稳定 `alarm_id` + dedup token；新表 `ReplacingMergeTree` | 允许物理重复，逻辑查询必须去重 | delivery outbox 负责重试，报表不按物理行计数 | `uniqExact(tenant_id, alarm_id)`；物理行数仅诊断 |
| SEARCH ingest event | `(tenant_id, event_id)` | `t_search_event` 与 ingestion outbox 唯一约束；相同内容重试返回 acknowledged/duplicates | 事务提交后重试只补缺失 Outbox，不重复写事件 | 数据库失败返回 503；仅重试未提交批次 | `payload_fingerprint`、唯一索引、`created/duplicates/acknowledged` 响应 |

## 约束

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

内容指纹使用 canonical payload 计算，JSON 字段顺序变化不改变身份。`ingested_at`
和 `fields.event_time_generated` 是归一化阶段的运行时元数据，不参与指纹；当采集端
没有提供有效事件时间时，归一化器会显式标记后生成事件时间，避免同一请求重试因当前
时间不同而误报 409。Outbox 载荷无法解析时按冲突处理（fail closed），不会把损坏的
发布意图当作安全重试确认。
