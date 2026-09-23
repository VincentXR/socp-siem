# SOAR 运维手册

这份手册对应 `services/soar-web` 的 SOAR durable execution surface。SOAR 控制面只接受已发布版本，执行请求先写入 PostgreSQL，再由 outbox worker 投递 Temporal；Temporal 不可用时运行保持 `QUEUED`，不会悄悄切换到进程内副作用执行器。

## 发布前检查

1. 使用 `python build/verify-soar.py` 做静态契约门禁。
2. 使用目标环境的 PostgreSQL、Temporal 和 reference connector 运行验证矩阵；H2、dry-run 和 mock 只能证明控制流，不代表厂商认证。
3. `prod` profile 必须配置 JWT/JWKS、Temporal、TLS 出站策略和 secret reference。不得把 token、密码或 secret value 写进 definition、inputs、日志或 Temporal payload。
4. 先发布低风险读动作，再单独认证 endpoint/firewall/EDR 的高风险动作；只有真实目标环境验收通过后才可把 connector 标记为 `PRODUCTION_READY`。

### Live evidence

在 PostgreSQL、Temporal、gateway 和至少两个 `soar-web` 实例均已就绪时运行：

```bash
SOAR_PRIMARY_URL=http://127.0.0.1:18092/soar-web \
SOAR_SECONDARY_URL=http://127.0.0.1:28083/soar-web \
SOAR_REQUIRE_SECONDARY=true \
python build/verify-soar-live.py
```

探针只创建 `START → DELAY → END` 的无副作用剧本，验证真实 Temporal 完成、事件
幂等和跨实例 `maxConcurrentRuns` 裁决；规则会禁用、剧本会归档，运行和 receipt
证据仍保留。设置 `SOAR_LIVE_EVIDENCE_PATH` 可将脱敏汇总写到指定 JSON；缺少
第二实例时可省略 `SOAR_REQUIRE_SECONDARY`，但结果只能作为单进程 smoke，不能作为
多实例发布证据。

## 健康与告警

- `/soar-web/actuator/health` 的 `UP` 表示可接收；`DEGRADED` 表示仍可接受但 Temporal、连接器或 backlog 超阈值；`DOWN` 表示不能安全接收。
- 重点指标：`soar_dispatch_backlog`、`soar_signal_backlog`、`soar_runs_active`、`soar_action_unknown_total`、`soar_trigger_suppressed_total`。
- dispatch/signal backlog 连续 5 分钟增长：检查 Temporal target、worker 日志和数据库连接池；不要直接把 `DISPATCHING` 改成成功。
- `ACTION_UNKNOWN` 必须先调用 reconcile（若连接器支持），再由有 `soar:operations` 权限的人员提交证据和原因到 `resolve-unknown`。禁止自动重试不可逆副作用。

## 死信处理

1. `GET /api/operations/dead-dispatches` 保存响应和 trace id。
2. 确认失败原因是瞬时依赖故障，而不是错误的 playbook/目标范围。
3. 修复依赖后调用 `POST .../{id}/requeue`；它会重置退避并保持原 run 的审计历史。
4. 不能安全重放时，填写不可为空的 reason 调用 `discard`；该 run 进入 `SUPPRESSED`，不能伪装成成功。

## 审批、取消和重跑

- 高风险动作在 dispatch 前进入 `WAITING_APPROVAL`；发起人不能批准自己的请求。拒绝和过期都必须带可审计原因。
- `cancel` 是协作信号：运行中的 Temporal workflow 先进入 `CANCELLING`，直到 workflow 产生最终状态。不要直接改数据库为 `CANCELLED`。
- `retry` 沿用 execution series 和已确认动作的幂等键，从安全失败节点继续；`rerun` 需要显式确认并生成新的 execution series，代表新的副作用。

## 数据保留与恢复

- Run/Node/Attempt/Event 默认保留 180 天，审计至少 365 天；artifact 默认 30 天，可按租户提高。
- 单个 artifact 的 inline JSON 上限 64 KiB；64 KiB 至 10 MiB 使用 artifact 存储（记录 SHA-256、classification 和 expiry）；超过 10 MiB 失败。
- artifact 读取再次进行租户和权限校验；过期记录由 `SoarArtifactRetentionWorker` 删除。对象存储 adapter 尚未配置时，`db://soar-artifacts/...` 只表示受控的本地 inline 存储，不代表外部对象存储 HA。
- 恢复顺序：先恢复 PostgreSQL，再恢复 Temporal server/worker，最后恢复 soar-web。dispatch/signal 的领取超过 120 秒后由有界扫描恢复，未耗尽预算的记录回到 `PENDING`，耗尽的进入 `DEAD`。

### Outbox 投递与 V23/V24 升级

Signal 的领取以租户、记录 ID、当前 `row_version` 和状态为条件，每次领取都增加版本和
`attempts`；最多领取 10 次。过期恢复、人工重排和重新写入决策都会使旧领取失效。
完成或失败回写必须匹配本次领取的版本与 worker。数据库没有确认回写时，记录仍由
后续恢复扫描处理；单条记录失败不阻断本批其他记录。每轮过期恢复和历史耗尽记录
清理各最多 100 条，并跳过被其他事务锁住的行。

只有 Temporal 确认接收后才记为 `SENT`；这表示投递回执，不表示工作流已完成审批
或动作。审批、人工任务和结果裁决使用连接已有 workflow ID 的 SDK stub；取消使用
带截止时间的 `SignalWorkflowExecution` RPC。不能使用尚未启动的新建 stub 发送信号。
缺少工作流 ID 时保留重试，找不到所属运行或信号类型/JSON/字段不合法时进入
`DEAD`，保留原始载荷供审查。禁止用空对象替代解析失败，也不把非布尔值转换成审批
拒绝。历史空 key 的合法审批/人工任务仍可投递；非空 durable key 必须与载荷中的 gate
或 node key 一致。Unknown resolution 仅接受 `CONFIRMED_SUCCEEDED` 和
`CONFIRMED_NOT_EXECUTED`，并要求 evidence/reason。

人工输入序列化后最多 256 KiB，完整信号最多 264 KiB（UTF-8）；新命令在同一事务中
验证后入队，超限或不合法返回 `SOAR_INVALID_SIGNAL`，不会提交已完成任务但无法投递的
半套状态。旧库中的超限信号会进入 `DEAD`，应先审查原因再决定修复或丢弃，不能靠反复
requeue 获得成功。

Dispatch 同样用版本区分每次领取，并在领取时计入最多 10 次的预算，包括崩溃后恢复。
领取、完成、失败和恢复通过短事务协调 run 与 outbox；回写必须同时满足租户、记录、版本、
worker 与领取状态。旧回调不能把新任务或已进入执行、等待、取消、终态的 run 改回
`QUEUED`/`DEAD`。每轮恢复最多读取 100 条候选，逐条重新检查版本，跳过被锁住的记录；
Temporal 调用在数据库事务之外。数据库拒绝完成回写时，保留可恢复的领取，不把它当作
远程启动失败。合法历史空 resume 字符串仍表示完整运行；损坏的 JSON、非字符串 resume、
不合法的执行预算或缺失版本进入 `DEAD`，不静默转成从头运行或默认预算。
输入与 definition 各最多 256 KiB（UTF-8）；retry/rerun 合并历史变量与恢复元数据后
重新检查输入大小，超限在创建 run/outbox 前返回 `413 SOAR_INPUT_TOO_LARGE`。

V23/V24 分别增加 signal/dispatch 过期扫描索引，复用已有版本列。上线前停止旧版 SOAR 实例的定时任务
并排空正在投递的请求，再启动新版；仅关闭 execution 开关不足以停止旧版恢复查询。
旧版批量 SQL 不递增版本，因此不能把混合版本运行视为具备上述隔离保证。

SOAR StartWorkflow 明确使用 `REJECT_DUPLICATE`，并只把匹配本 workflow ID 的 SDK
`WorkflowExecutionAlreadyStarted` 异常视为重复接收；普通错误文本不能作为成功证据。
该策略对已结束工作流的去重受 Temporal namespace retention 限制，不能据此声称永久
exactly-once。运行记录及副作用收据仍需保留，`retry`/`rerun` 使用新的运行 ID。
参见 [Temporal Workflow ID 策略](https://docs.temporal.io/workflow-execution/workflowid-runid#workflow-id-reuse-policy)。

### 取消与停滞恢复（V25）

取消信号被接收不代表工作流已经结束，也不代表活动有新进展。取消重试只更新
`cancel_next_attempt_at`，恢复查询只更新 `recovery_next_check_at`；这些字段由数据库
原子领取维护，不修改 `updated_at` 或业务 `row_version`，普通实体保存不会覆盖它们。
取消最早 30 秒后再试，恢复最早 60 秒后再查。每轮最多读取 100 条，未查询过的记录
优先；开始下一条前检查 10 秒批次预算，已开始的条目可以完成。领取跳过其他事务
锁住的行，单条故障不回滚整批，也不会长期占住列表前 100 个位置。

Describe 和取消 RPC 各有 3 秒截止时间，均在数据库事务之外。恢复仅在 Temporal 明确
返回已关闭状态，或返回同一活动集群的带类型 `NotFoundFailure` 后，进入短事务锁定 run，
重新检查原业务版本和停滞时间。运行中、暂停、continue-as-new、缺失/未知状态、超时、
namespace 错误、备用集群的缺失响应以及无类型 `NOT_FOUND` 都保留当前状态。
错误类型来自 [Temporal 错误协议](https://github.com/temporalio/api/blob/master/temporal/api/errordetails/v1/message.proto)。
只有仍待投递的 dispatch 也已退出 `PENDING`/`DISPATCHING`，才允许恢复终态；正在等待
审批或人工输入且已有 workflow 的运行同样接受检查，尚未启动的审批等待不会被判为孤儿。

确认关闭/缺失后，`CANCELLING` 才进入 `CANCELLED`。尚有 `RUNNING` 的动作收据时保留该
证据并提示核查；取消不撤销外部副作用，也不允许普通 retry 重新启动已取消运行。
其他活动状态有未确认动作时进入 `ACTION_UNKNOWN`；没有运行中动作时分别进入 `FAILED`
或 `TIMED_OUT`。观察期间如果活动、操作员或其他恢复任务更新了业务版本，旧结果被丢弃。
本地缺少 workflow ID 时使用 `soar-{tenant}-{runId}` 查询，不凭空认定远程不存在。

V25 增加两个调度时间列及索引。升级时停止并排空旧 SOAR worker；旧取消任务会继续刷新
业务进度，旧恢复任务也没有上述确认约束。判断针对当前配置的 Temporal namespace，
不会跨 namespace 自动寻找历史工作流；切换 namespace 前须核对、迁移未完成运行。
只返回无类型缺失错误的旧服务会保留待确认状态，需要修复服务响应或核查历史记录。

## 事故处置底线

不要用 SQL 直接把 `FAILED`、`ACTION_UNKNOWN`、`CANCELLING` 改成成功；不要删除事件、attempt、receipt 或审批记录；不要在未完成目标环境认证前打开高风险 connector。所有人工修复都应通过 API 并保留审计事件。

## Secret provider configuration

Preview defaults to `env://` references. Production defaults to the Kubernetes
projected-volume provider; each `k8s://namespace/secret/key` lookup reads the
mounted file on demand, so atomic Secret rotation is visible without a
restart. Set `SOCP_SOAR_SECRET_BACKEND=vault` for Vault KV references in the
form `vault://mount/path#field`; configure an HTTPS `SOCP_SOAR_VAULT_ENDPOINT`
and a `SOCP_SOAR_VAULT_TOKEN_REF` backed by `k8s://`. Production artifact
access/secret references must use the selected rotatable provider (`k8s://`
for Kubernetes or `vault://` for Vault); environment fallback is disabled.
Secret values are never persisted in definitions, Temporal payloads, logs, or
API responses. The `/health` payload reports the selected resolver without
exposing its configuration or values.
