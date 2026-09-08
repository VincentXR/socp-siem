# SOAR V2 整改对照台账
更新时间：2026-09-08。本文将原审查报告中的问题逐项映射到当前代码、测试和
尚未取得的部署证据。`已修复`表示代码路径和本地测试已覆盖；不等同于目标环境
已经完成发布验收。

## 按整改顺序

| 顺序 | 报告问题 | 当前状态 | 代码/测试证据 | 尚缺证据或边界 |
| --- | --- | --- | --- | --- |
| 1 | P0 Alert → V2 主链路断裂 | **代码已修复，已取得隔离环境 live 验收** | `SoarClient` 使用 `/api/v2/events/evaluate`；V1 evaluate 仅在 V2 evaluation 未开启时检查 legacy；V2 controller 将告警 envelope 送入 durable automation rule；`build/verify-soar-live.py` 在双 SOAR + Temporal 环境创建短时无副作用剧本并验证事件产生可完成 Run，`build/verify-full.py` 继续验证真实 Alert 服务入口。 | 本地 live 验收覆盖用户鉴权事件；真实 Alert Web 服务签名入口仍由 full-stack workflow 验证。 |
| 2 | P0 子剧本发布、递归、深度和全局预算 | **代码已修复，基础 Temporal live 验收已接入** | 发布、准入和复制边界递归检查已发布版本、环、内联定义和最大深度 5；Temporal child/branch 共享根 Run；V21 `execution_node_count` 在 Activity 行锁下预留节点槽位；live verifier 使用真实 Temporal 完成 bounded `DELAY` Run。 | 仍需目标 PostgreSQL + Temporal 的子剧本 replay、并发、故障恢复和容量压测证据。 |
| 3 | P1 Automation Rule 多实例幂等/容量 | **部分修复，已接入双实例 live 验收** | enabled rule 使用数据库 `PESSIMISTIC_WRITE`；receipt 唯一键含 revision；requestId 含 revision；容量按整组 action 计数并在同一事务判断；`SoarV2PostgresMigrationContractTest` 实测 receipt 唯一键和跨连接 `FOR UPDATE` 竞争；full-stack 启动第二个 `soar-web`，live verifier 验证同事件单 Run/双 receipt 和跨实例 `maxConcurrentRuns=1` 裁决。 | 尚无独立 admission/reservation 表；手工运行等其他入口不自动占用规则容量；仍需实际 workflow 执行后的压测与故障恢复证据。 |
| 4 | P1 生产证据链不足 | **部分修复，full-stack live 证据已接入并完成一次隔离实证** | CI 已纳入 `services/soar-web` 的全量测试、`SoarV2PostgresMigrationContractTest` 和选定 Temporal workflow contracts；故障脚本已改为 Temporal 不可用时保持 durable `QUEUED`，不回退进程内执行器；full-stack 现在启动真实 PostgreSQL/Temporal、主/第二 SOAR 实例，并运行 `build/verify-soar-live.py`；结果写入 `.cache/soar-v2-live.json`。本次隔离环境实跑生成 `.cache/soar-v2-live-local.json`，24 项通过、0 失败。 | 尚未执行 GitHub workflow 的完整运行日志；Workbench 完整流程、故障恢复和长期压测仍待补齐。 |
| 5 | P1 Workbench 没有 SOAR V2 浏览器流程 | **未完成** | 当前 Playwright 主要覆盖登录、角色菜单、路由和 gateway smoke。 | 仍需创建/编辑、校验/发布、执行、审批、人工输入、恢复、SSE 和权限负例的浏览器流程及截图/可访问性验收。 |
| 6 | P2 OpenAPI cookie、ApiResult、If-Match/ETag | **基础契约已修复，SDK 完整度未达标** | snapshot 使用真实 `SOCP_SESSION`；包含 `ApiResult` envelope、`If-Match` 参数和 ETag 响应头；`docs/api-contract.md` 明确 runtime `/v3/api-docs` 为准。 | `data` 仍是 endpoint-specific 泛型，尚未对每个运行时文档做部署 smoke 和 SDK 生成验证。 |
| 7 | P2 Java 正则 ReDoS | **代码级缓解已完成** | 人工表单 pattern 限长 256，并拒绝 lookaround、back-reference、嵌套量词、量词分支组和多个无界量词；覆盖常见恶意样例。 | 仍基于 Java backtracking `Pattern`，没有线性时间形式化保证；需安全负例、长输入压力和超时监控证据。 |
| 8 | P2 secret:// 仅环境变量 | **代码边界已修复** | 预览环境保留 env resolver；生产提供按 lookup 读取的 Kubernetes projected-volume 和 Vault KV HTTP resolver；生产 guard 要求 HTTPS、可轮换 provider 和 `k8s://`/`vault://` 引用。 | 尚未提供 KMS/HSM；需要目标集群轮换、权限、Vault 不可用和恢复演练证据。 |
| 9 | P2 SOAR 表缺少外键 | **迁移与清理代码已修复** | V22 为 playbook/version/run/dispatch/node/event/approval/receipt/attempt/manual/signal/artifact 建立带 `tenant_id` 的复合外键和唯一键；`SoarRunRetentionWorker` 按 dispatch/approval-decision/approval/manual/signal/node/attempt/run 顺序清理，并在 timeline/artifact 尚未过期时保留父 Run；`SoarV2PostgresMigrationContractTest` 在 PostgreSQL 16 中验证 22 个迁移可重复执行及跨租户 Run 被数据库拒绝。 | 仍需目标环境清理历史孤儿数据并验证回滚/升级路径。 |
| 10 | P2 大 artifact inline PostgreSQL | **代码边界已修复** | 大于 64 KiB 且不超过 10 MiB 的脱敏 payload 写入 S3/MinIO 兼容存储，数据库仅留引用、大小和 SHA-256；读回校验完整性；保留任务先删对象再删元数据。 | 仍需对象存储版本化、保留/恢复、HA、密钥轮换、生命周期和指标告警；事务提交后异常的孤儿对象需后台对账。 |
| 11 | P2 大类服务长期演进 | **未完成** | `SoarV2Service`、`SoarV2WorkflowImpl`、`SoarV2AutomationRuleService` 仍是大类。 | 后续拆分 application/domain/projection/admission/connector 边界，并以行为不变和性能基线验收。 |

## 当前发布结论

当前不是“所有问题都已修复”，而是 P0 的代码阻断已解除，P1 的核心并发语义已
明显加强，P2 的契约、正则、secret、外键和 artifact 已有可审查实现；生产证据链、
真实基础设施演练、Workbench 完整流程、对象存储运营能力和架构拆分仍未闭环。

因此仍应维持：

1. 允许个人项目、演示、实验室和受控只读/低风险试点；
2. 在真实 PostgreSQL + Temporal + Alert → Run + Playwright 验收前，不宣称通用生产完成；
3. endpoint/firewall/EDR 等高风险无人值守动作继续要求真实目标认证、审批和
   `PRODUCTION_READY` 门禁。

## 本次本地验证

- `python build/verify-soar.py`：静态 SOAR 契约检查通过（部署探针因未配置 URL 而跳过）；
- `python -m unittest discover -s build/tests -p 'test_*.py'`：15 项通过，包含 live
  verifier 的 URL、envelope、边界剧本和租户 trace 单元检查；
- `python build/verify-soar-live.py`：使用隔离 PostgreSQL 18 + Temporal 1.24 + 双 SOAR
  实例实跑，24 项通过、0 失败、0 警告；结果保存在 `.cache/soar-v2-live-local.json`（未纳入提交）；
  full-stack CI 已接入同一命令并保留 `.cache/soar-v2-live.json`；
- `python build/verify-style.py`、`git diff --check`：通过；
- `SoarV2PostgresMigrationContractTest`（PostgreSQL 16 Testcontainers）：1 项通过；
- 同一测试覆盖跨连接规则行锁（`55P03`）与 receipt 唯一键（`23505`）边界；
- SOAR V2 定向服务/定义/automation/Temporal/artifact 测试：通过；
- S3、Kubernetes、Vault resolver 测试：13 项通过（Windows 默认临时目录清理存在
  JUnit 权限竞态，改用工作区临时目录重跑通过）；
- `ProdGuardTest`：20 项通过。
