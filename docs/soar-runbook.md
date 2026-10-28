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

- 初次启动时 Temporal namespace 不可达，Worker 注册可能失败；独立恢复线程每次失败后等待 5 秒重试，不占用 dispatch/signal/审批过期的共享调度线程。依赖恢复后无需重启 SOAR；已启动 Worker 的连接重试仍由 Temporal SDK 管理，停用或关闭的 Worker 不会被恢复线程重新启动。
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

- 显式 `APPROVAL` 节点先在同一事务中持久化 `WAITING_APPROVAL` 节点记录，再创建引用它的审批记录。重试复用节点身份，审批结束后更新该记录；不要删除外键来规避节点缺失错误。
- 人工任务使用独立的节点运行记录；超时事件引用节点 ID，不能引用待办 ID。新运行的零秒超时会过期，不能在未提交必填输入时成功；超时后仅可走显式 `timeout` 分支，没有该分支时失败，不回退到普通完成连线。未显式设置 dueAt 时，页面期限取节点等待时间与运行剩余时间的较小值。Temporal version marker 保留旧历史的重放语义。
- 新自动规则执行记录的 `triggerType` 为 `AUTOMATION`，手动入队仍为 `MANUAL`；规则和事件身份保留在原 request/input/receipt 中。已有历史运行不回写类型，retry/rerun 继续使用自己的类型。
- 高风险动作在 dispatch 前进入 `WAITING_APPROVAL`；发起人不能批准自己的请求。拒绝和过期都必须带可审计原因。
- `cancel` 是协作信号：运行中的 Temporal workflow 先进入 `CANCELLING`，直到 workflow 产生最终状态。不要直接改数据库为 `CANCELLED`。
- `retry` 沿用 execution series 和已确认动作的幂等键，从安全失败节点继续；`rerun` 需要显式确认并生成新的 execution series，代表新的副作用。

### 终态人工任务与取消竞态

运行取消或达到总执行期限时，在锁定所属 run 的同一事务内关闭所有仍为 `PENDING`
的人工任务和审批，并结束 `WAITING_INPUT`/`WAITING_APPROVAL` 节点。取消使用
`CANCELLED`；总期限超时（含 `FAILED + EXECUTION_TIMEOUT`）使用任务/审批 `EXPIRED`
和节点 `TIMED_OUT`。已提交输入、已作出的审批决定及动作执行收据保留；终态不允许
迟到的人工输入或审批重新启动工作流。所有人工决策和到期处理按 run → task/approval
顺序加锁，避免与终态更新形成反向等待。

升级前已存在的终态残留由 `SoarTerminalWorkWorker` 自动收敛：启动后默认 5 秒首次
扫描，随后每 30 秒最多选取 100 个仍有待办/等待节点的终态运行，每条使用独立短事务，
跳过被锁定的 run，候选查询与单条事务各设置 5 秒超时，批次预算 10 秒。
重复扫描只更新仍待处理的记录，
不会生成人工决定、发送信号、重试动作或声称撤销外部副作用。可通过
`socp.soar.terminal-work-poll-ms` 和 `socp.soar.terminal-work-initial-delay-ms` 调整节奏。

节点预算准入现在区分取消、预算耗尽和投影缺失：提前取消返回 `CANCELLED`，不能
误报 `EXECUTION_LIMIT_EXCEEDED`。`soar-node-admission-outcome` Temporal version
marker 保留旧历史的 boolean Activity 命令；新增 `reserveNodeExecutionOutcome`
Activity 必须随本版 worker 一同发布。滚动发布应排空旧 worker 的轮询或使用 Temporal
worker 版本路由，确保新版历史不会被不识别新 Activity 的旧 worker 接收。

取消 worker 在每次受控重试中同时核实 Temporal 状态。即使已关闭的工作流拒绝取消
信号，只要独立 Describe 权威确认 `CLOSED`/`NOT_FOUND`，且业务版本未变化、dispatch
已退出待启动状态，就及时收敛 `CANCELLING`，无需等待普通停滞恢复阈值。信号成功、
网络错误或不确定的 Describe 都不能单独作为结束证明；运行中的动作收据继续保留。

前端复测应分别覆盖正常人工完成、等待时取消、总期限先于人工节点期限、开始后立即
取消及审批等待时取消；检查运行、节点、人工任务/审批三处状态一致，刷新后仍一致，
正常路径仍可提交并执行到结束。历史残留应在自动扫描后消失，不通过数据库手工改状态。

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


## Workbench catalog paging and retained run history (V27)

The playbook catalog, run picker, automation rules, connections and manual-task
lists use bounded, zero-based server pages. Workbench pages contain 25 items;
operators can navigate past the former first-100 cutoff. Playbook/version
pickers first select a page of playbooks and only request versions for the
selected playbook. Hidden or replaced reads cannot overwrite newer selections.
Catalog order includes an ID tie-breaker so equally dated rows remain stable.
Offset pages are live views rather than snapshots: concurrent inserts or edits
can shift rows between pages, and Refresh reloads the current page.

The playbook catalog's `latestRun` comes from retained history for that playbook
across all revisions, independent of the most recent tenant-wide runs. A null
summary means no retained runs, not proof that the playbook never ran. Clients
that receive no summary field from an older server show an unknown marker rather
than inferring history from a partial run list. Run retention policy is unchanged.

V27 adds the tenant/playbook/created-at/ID lookup index for the bounded summary
query; it does not change run records or authentication. Apply it with the normal
SOAR Flyway migration before rollout and budget index-build time for large run
histories. The H2 repository regression fixture includes 137 rows per catalog,
old retained runs absent from the latest 20 results, tied sort values, soft-deleted
connections and a second tenant. PostgreSQL migration/query behavior still needs
the existing PostgreSQL contract checks in deployment validation.

## 历史线性剧本兼容验证与退役

旧定时任务和旧审批任务仍使用 `PlaybookExecutor`；保留
`PlaybookWorkflow` worker，不能只保留 `SoarWorkflow` worker。新图剧本的
持久队列接收契约不改变上述存量任务的分派语义。退役条件见
[历史兼容边界](soar-design.md#1711-仍在运行的历史兼容边界)。

本地回归运行：

```bash
bash build/mvnw.sh -pl services/soar-web -am test \
  -Dtest=PlaybookWorkflowCompatibilityTest,LegacyPlaybookActionsTest,PlaybookExecutorTest,ScheduledPlaybookRunnerTest,ApprovalServiceTest,SoarActivityImplCoverageTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

合成 history 覆盖空剧本、成功/旧 success 别名、模拟、失败后跳过、补偿失败后继续、
无前序失败的补偿及未知旧状态。测试核对原有活动参数和结果，并回放整理前实现
生成的 history；它不证明生产 history 已迁移或旧执行器可以删除。发布前仍需
目标环境 PostgreSQL/Temporal 的 live 验证和实际 history 抽样回放。


### Interruptible delay nodes

New workflow histories use the `interruptible-delay` version marker. A cancel
signal wakes an active DELAY, records the node and run as CANCELLED, and does
not execute the next node. The published execution deadline still bounds an
uncancelled delay. Histories that scheduled the legacy timer before upgrade
retain the old timer path for deterministic replay; their pending delay may
finish before cancellation settles. Do not edit persisted run states to bypass
Temporal completion or replay compatibility.
