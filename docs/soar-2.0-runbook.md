# SOAR 2.0 运维手册

这份手册对应 `services/soar-web` 的 V2 durable execution surface。V2 的控制面只接受已发布版本，执行请求先写入 PostgreSQL，再由 outbox worker 投递 Temporal；Temporal 不可用时运行保持 `QUEUED`，不会悄悄切换到进程内副作用执行器。

## 发布前检查

1. 使用 `python build/verify-soar.py` 做静态契约门禁。
2. 使用目标环境的 PostgreSQL、Temporal 和 reference connector 运行验证矩阵；H2、dry-run 和 mock 只能证明控制流，不代表厂商认证。
3. `prod` profile 必须配置 JWT/JWKS、Temporal、TLS 出站策略和 secret reference。不得把 token、密码或 secret value 写进 definition、inputs、日志或 Temporal payload。
4. 先发布低风险读动作，再单独认证 endpoint/firewall/EDR 的高风险动作；只有真实目标环境验收通过后才可把 connector 标记为 `PRODUCTION_READY`。

### Live V2 evidence

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

1. `GET /api/v2/operations/dead-dispatches` 保存响应和 trace id。
2. 确认失败原因是瞬时依赖故障，而不是错误的 playbook/目标范围。
3. 修复依赖后调用 `POST .../{id}/requeue`；它会重置退避并保持原 run 的审计历史。
4. 不能安全重放时，填写不可为空的 reason 调用 `discard`；该 run 进入 `SUPPRESSED`，不能伪装成成功。

## 审批、取消和重跑

- 高风险动作在 dispatch 前进入 `WAITING_APPROVAL`；发起人不能批准自己的请求。拒绝和过期都必须带可审计原因。
- `cancel` 是协作信号：运行中的 Temporal workflow 先进入 `CANCELLING`，直到 workflow 产生最终状态。不要直接改数据库为 `CANCELLED`。
- `retry` 沿用 execution series 和已确认动作的幂等键，从安全失败节点继续；`rerun` 需要显式确认并生成新的 execution series，代表新的副作用。

## 数据保留与恢复

- Run/Node/Attempt/Event 默认保留 180 天，审计至少 365 天；artifact 默认 30 天，可按租户提高。
- 单个 inline JSON 上限 64 KiB；64 KiB 至 10 MiB 写入 artifact（记录 SHA-256、classification 和 expiry）；超过 10 MiB 失败。
- artifact 读取再次进行租户和权限校验；过期记录由 `SoarArtifactRetentionWorker` 删除。对象存储 adapter 尚未配置时，`db://soar-artifacts/...` 只表示受控的本地 inline 存储，不代表外部对象存储 HA。
- 恢复顺序：先恢复 PostgreSQL，再恢复 Temporal server/worker，最后恢复 soar-web。outbox stale claim 会在约 120 秒后重新变为 `PENDING`，重复 start 由稳定 workflow id 去重。

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
