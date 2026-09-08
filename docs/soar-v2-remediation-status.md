# SOAR V2 整改对照台账
更新时间：2026-09-08。本文将原审查报告中的问题逐项映射到当前代码、测试和
尚未取得的部署证据。`已修复`表示代码路径和本地测试已覆盖；不等同于目标环境
已经完成发布验收。

## 按整改顺序

| 顺序 | 报告问题 | 当前状态 | 代码/测试证据 | 尚缺证据或边界 |
| --- | --- | --- | --- | --- |
| 1 | P0 Alert → V2 主链路断裂 | **代码已修复** | `SoarClient` 使用 `/api/v2/events/evaluate`；V1 evaluate 仅在 V2 evaluation 未开启时检查 legacy；V2 controller 将告警 envelope 送入 durable automation rule。 | 尚未在本工作区启动完整 middleware，未取得真实 Alert → Run 的部署级 E2E 证据。 |
| 2 | P0 子剧本发布、递归、深度和全局预算 | **代码已修复** | 发布、准入和复制边界递归检查已发布版本、环、内联定义和最大深度 5；Temporal child/branch 共享根 Run；V21 `execution_node_count` 在 Activity 行锁下预留节点槽位。 | 需要目标 PostgreSQL + Temporal 的 replay、并发、故障恢复和容量压测证据。 |
| 3 | P1 Automation Rule 多实例幂等/容量 | **部分修复** | enabled rule 使用数据库 `PESSIMISTIC_WRITE`；receipt 唯一键含 revision；requestId 含 revision；容量按整组 action 计数并在同一事务判断。 | 尚无独立 admission/reservation 表；手工运行等其他入口不自动占用规则容量；尚未完成真实多实例 PostgreSQL 竞争测试。 |
| 4 | P1 生产证据链不足 | **部分修复** | CI 已纳入 `services/soar-web` 的全量测试和选定 Temporal workflow contracts；故障脚本已改为 Temporal 不可用时保持 durable `QUEUED`，不回退进程内执行器；full verify 查询 V2 Run。 | CI workflow contracts 使用 in-process Temporal test environment；full-stack job 未启动 soar-web/Temporal；故障脚本和部署探针在本环境未执行。 |
| 5 | P1 Workbench 没有 SOAR V2 浏览器流程 | **未完成** | 当前 Playwright 主要覆盖登录、角色菜单、路由和 gateway smoke。 | 仍需创建/编辑、校验/发布、执行、审批、人工输入、恢复、SSE 和权限负例的浏览器流程及截图/可访问性验收。 |
| 6 | P2 OpenAPI cookie、ApiResult、If-Match/ETag | **基础契约已修复，SDK 完整度未达标** | snapshot 使用真实 `SOCP_SESSION`；包含 `ApiResult` envelope、`If-Match` 参数和 ETag 响应头；`docs/api-contract.md` 明确 runtime `/v3/api-docs` 为准。 | `data` 仍是 endpoint-specific 泛型，尚未对每个运行时文档做部署 smoke 和 SDK 生成验证。 |
| 7 | P2 Java 正则 ReDoS | **代码级缓解已完成** | 人工表单 pattern 限长 256，并拒绝 lookaround、back-reference、嵌套量词、量词分支组和多个无界量词；覆盖常见恶意样例。 | 仍基于 Java backtracking `Pattern`，没有线性时间形式化保证；需安全负例、长输入压力和超时监控证据。 |
| 8 | P2 secret:// 仅环境变量 | **代码边界已修复** | 预览环境保留 env resolver；生产提供按 lookup 读取的 Kubernetes projected-volume 和 Vault KV HTTP resolver；生产 guard 要求 HTTPS、可轮换 provider 和 `k8s://`/`vault://` 引用。 | 尚未提供 KMS/HSM；需要目标集群轮换、权限、Vault 不可用和恢复演练证据。 |
| 9 | P2 SOAR 表缺少外键 | **迁移代码已修复** | V22 为 playbook/version/run/dispatch/node/event/approval/receipt/attempt/manual/signal/artifact 建立带 `tenant_id` 的复合外键和唯一键。 | 需要在真实 PostgreSQL 执行迁移、清理历史孤儿数据并验证回滚/升级路径。 |
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
- `python build/verify-style.py`、`git diff --check`：通过；
- SOAR V2 定向服务/定义/automation/Temporal/artifact 测试：通过；
- S3、Kubernetes、Vault resolver 测试：13 项通过（Windows 默认临时目录清理存在
  JUnit 权限竞态，改用工作区临时目录重跑通过）；
- `ProdGuardTest`：20 项通过。
