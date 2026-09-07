# SOCP SOAR 2.0 设计书

> 状态：实施基线（Implementation Baseline）
> 版本：1.0
> 日期：2026-09-03
> 适用范围：`services/soar-web`、`frontend/apps/workbench` 及其与 Alert、Incident、Search、Asset、HIPS、Threat、Notify 的集成
> 读者：后端、前端、测试、安全与平台工程师，以及负责后续实现的 coding agent

## 1. 结论

当前 `soar-web` 不是可投入真实 SOC 使用的 SOAR，只是一个具备少量安全护栏的线性动作执行原型。它保留了一些值得复用的基础设施，但剧本模型、编排语义、连接器体系、运行可观测性和操作台都需要按新模型重写。

本设计的目标不是堆几个演示按钮，而是在现有 Java 21/Spring Boot、Vue 3、PostgreSQL、Temporal 和统一认证体系上，交付一个达到主流 SIEM/SOAR **核心能力基线**的 SOAR 2.0：

- 可视化、可版本化、可发布和可回滚的剧本；
- Alert、Incident、Entity、Schedule、Webhook 和 Manual 六类触发；
- 条件、分支、并行、汇聚、有界循环、延迟、子剧本、审批和人工任务；
- 类型化数据映射、安全表达式和可追踪的节点输入输出；
- 连接定义、连接实例、密钥引用、连通性测试和动作目录；
- 真正异步、崩溃可恢复、逐节点持久化的执行引擎；
- 高风险动作的职责分离、目标范围限制、回执校验和不确定结果处置；
- 完整的运行详情、审计、指标、日志、链路和失败恢复入口；
- 与告警、案件、资产、检索、威胁情报、终端和通知模块形成闭环。

“达到主流水平”在本设计中指功能完整性、执行安全和可运营性达到商业 SOAR 的核心基线，不声称首版即拥有大型厂商数百个连接器的生态规模，也不声称未经目标环境验收就具备生产 HA。

## 2. 现状审计

### 2.1 已有实现

当前实现包含：

- `Playbook` 元数据和 `t_playbook` 持久化；
- 告警评估去重、定时触发 claim、执行摘要和审批记录；
- `soar:execute`、`soar:approve` 权限及租户上下文；
- 通知、建案、Webhook、隔离/防火墙/快照适配器的初步 handler；
- 外部动作幂等键和“不能只凭 HTTP 2xx 判断成功”的连接器回执约束；
- 可选 Temporal Workflow，以及开发环境进程内回退；
- 剧本列表、创建、启停、删除和最近执行表格。

这些能力说明模块并非完全空白，但只构成重写时可复用的地基。

### 2.2 关键缺口

| 领域 | 当前证据 | 问题 | SOAR 2.0 要求 |
|---|---|---|---|
| 剧本模型 | `Playbook.java` 是 `trigger: String + actions: List<String>` | 无图、节点、边、端口、输入输出、错误路径和版本 | 不可变发布版本 + 类型化工作流定义 |
| 触发匹配 | `PlaybookExecutor.matches()` 做字符串包含判断 | 条件不可验证、不可解释，易误触发 | 独立 Automation Rule + 类型化事件 + CEL 条件 |
| 执行流 | `PlaybookWorkflowImpl` 顺序遍历字符串 | 无条件、并行、循环、等待、人工任务和子剧本 | 持久图解释器和明确的节点状态机 |
| API | 创建、删除、toggle、同步 execute、最近 200 条执行 | 无更新、草稿、发布、分页、取消、详情、节点日志 | 完整控制面和异步运行 API |
| Temporal | HTTP 请求同步等待 Workflow 完成 | 长任务占用请求；无 signal/update；历史兼容策略缺失 | `202 Accepted`、稳定 Workflow ID、Signal、Worker 安全部署 |
| 降级 | Temporal 不可用时进程内执行副作用 | 生产语义分叉，重启丢状态 | 生产 fail-closed；只允许持久化排队，不执行内存降级 |
| 动作 | 从字符串猜动作类型，handler 固定注册 | 无动作 schema、连接实例、密钥管理和能力发现 | Connector SDK + Action Catalog + Connection |
| 审批 | 基于整个剧本和动作字符串判断 | 不能逐节点、双人复核、拒绝/过期分支和防自批 | 节点级审批、策略评估、职责分离 |
| 运行记录 | 一行 execution + `results_json` | 无节点尝试、等待态、未知态、远程回执和实时事件 | Run/Node/Attempt/Event 四层投影 |
| 安全 | 有权限注解，但剧本变更未统一审计 | 无发布权分离、SSRF 策略、secret redaction、目标范围 | 细粒度 RBAC、密钥引用、出站策略、全操作审计 |
| 前端 | `SoarView.vue` 只有两个表格和文本输入 | 不能构建、测试、排障或审批真实流程 | 五个工作台 + 可视化编辑器 + 运行检查器 |
| 集成上下文 | `AlarmEvaluationRequest` 丢弃风险分、证据等字段 | 剧本拿不到调查所需上下文 | 版本化事件信封，保留实体、证据、风险和因果链 |

### 2.3 必须保留的能力

重写不得破坏以下现有约束：

1. 所有读写按 `TenantContext` 隔离，Repository 查询必须显式带 `tenant_id`，生产 PostgreSQL 保留 RLS 防线。
2. 内部触发入口继续使用服务身份，不能用普通用户 JWT 冒充 Alert Web。
3. 现有 `(tenant_id, alarm_id)` 告警评估去重和 `(tenant_id, playbook_id, scheduled_for)` 调度 claim 的意图必须迁移到新 receipt 模型。
4. 外部动作继续携带稳定幂等键；高风险动作必须验证业务回执，HTTP 2xx 本身不是完成证明。
5. `prod` 禁止 simulation、默认密钥、HTTP/不可信 TLS 和非持久执行回退。
6. OpenTelemetry trace、`ApiResult`、OpenAPI、统一异常和 `socp-audit` 必须接入，而不是另建一套横切框架。
7. 现有 `/api/v1/playbooks` 契约在迁移窗口保留兼容适配并标记 deprecated，前端和服务调用者切换完成、公告窗口结束后才移除。

## 3. 行业能力基线

设计参考以下官方能力，而不是照搬任一厂商的数据模型：

- Microsoft Sentinel 将 Automation Rule 与 Playbook 分开，支持 incident/alert/entity 触发、自动和手工执行及运行历史；
- Splunk SOAR 提供 Action、Playbook、Utility、Filter、Decision、Prompt、循环，以及 app/asset（连接器定义/实例）模型；
- Cortex XSOAR 强调任务、条件、子剧本、用户交互、上下文数据和处置闭环；
- OASIS CACAO 2.0 定义了 start/end、action、playbook-action、parallel、if/while/switch 等可交换安全剧本概念。

### 3.1 能力分级

| 优先级 | 含义 | 发布约束 |
|---|---|---|
| P0 | SOAR 2.0 可用基线 | 所有 P0 验收通过才允许替换旧模块 |
| P1 | 主流增强能力 | 不阻塞 P0，但数据模型必须预留且不能返工 P0 |
| P2 | 生态和高级能力 | 独立路线，不得以不安全捷径混入 P0 |

### 3.2 能力矩阵

| 能力 | P0 | P1 | P2 |
|---|:---:|:---:|:---:|
| 可视化工作流、校验、草稿、发布、复制、历史版本 | 是 | 版本 diff/回滚 | 协同编辑 |
| Alert/Incident/Entity/Schedule/Webhook/Manual 触发 | 是 | Detection Rule 直接触发 | 邮箱/外部消息总线 |
| 条件、switch、并行、join、foreach、delay | 是 | while、复杂聚合 | BPMN 互操作 |
| 审批、拒绝、超时、人工任务 | 是 | 多级/双人审批模板 | 外部审批系统联动 |
| 子剧本、变量、节点输出引用 | 是 | 可复用函数节点 | 安全沙箱脚本 |
| 连接器目录、连接实例、secret ref、test connection | 是 | 连接器包热安装 | 签名市场和远程 Broker |
| SOCP 内置连接器与通用 HTTP | 是 | 厂商 EDR/Firewall/ITSM 连接器 | 大规模第三方生态 |
| 异步执行、节点级状态、取消、超时、重试、回执 | 是 | 从失败节点安全续跑 | 跨区域执行 |
| 搜索、筛选、分页、实时事件、统计 | 是 | 成本/节省工时分析 | 流程挖掘 |
| JSON 导入导出 | 是 | CACAO 2.0 导入导出 | 内容签名与共享中心 |

## 4. 目标与非目标

### 4.1 P0 产品目标

1. 分析员能在 UI 中从模板创建剧本，配置输入、条件和动作，测试后提交发布。
2. 发布者能看到确定性的校验结果和风险摘要；已发布定义不可原地修改。
3. 告警或案件事件被系统接受后，即使 SOAR/Temporal Worker 重启也不会丢失。
4. 一个运行可以等待数小时的审批或人工输入，而不占用 HTTP 请求或 JVM 线程。
5. 分析员能看到每个节点何时开始、用了什么非敏感输入、调用哪个连接、返回何种回执、为什么失败以及下一步怎么处理。
6. 高风险动作默认不能自动执行，审批人不能审批自己发起的请求。
7. 连接密钥不进入剧本定义、Temporal History、数据库结果、API 响应、日志或审计详情。
8. 告警调查可以完成“富化 → 判断 → 建案/更新案件 → 通知 → 经审批遏制 → 验证 → 留痕”的闭环。

### 4.2 非目标

- P0 不执行用户提交的 JavaScript、Python、Shell 或 SpEL。
- P0 不实现任意 JAR/ZIP 动态插件加载；连接器随受控构建发布。
- P0 不替代 `incident-web` 的案件管理，也不复制 `search-config` 的搜索存储。
- P0 不承诺没有幂等/查询能力的第三方系统实现 exactly-once 副作用。
- P0 不实现跨地域 active-active Temporal/PostgreSQL。
- P0 不以 H2、内存队列或模拟连接器作为生产就绪证据。
- AI 可以在后续生成草稿或建议参数，但不得绕过验证、发布和审批流程。

## 5. 核心领域模型

### 5.1 术语

| 术语 | 定义 |
|---|---|
| Playbook | 稳定身份和元数据容器，本身不包含可变的线上逻辑 |
| Playbook Version | 某次不可变工作流定义；草稿可编辑，发布后冻结 |
| Automation Rule | 事件触发、条件、优先级、抑制和目标剧本版本的绑定 |
| Connector | 一类产品或平台集成，发布一组 Action Definition |
| Connection | 某租户对 Connector 的一个配置实例，类似 SOAR asset |
| Action Definition | 动作 ID、输入/输出 schema、风险、幂等、超时和权限元数据 |
| Run | 一次剧本执行，固定引用一个已发布版本及输入快照 |
| Node Run | 某节点在某次 Run、某个循环迭代中的状态投影 |
| Attempt | 一次真实外部调用尝试及其回执 |
| Approval Task | 执行危险节点前的审批闸门 |
| Manual Task | 需要分析员提供结构化输入或完成线下步骤的任务 |
| Trigger Receipt | 对一个来源事件和自动化规则的幂等处理凭证 |

### 5.2 生命周期

Playbook 元数据状态：

```text
ACTIVE -> ARCHIVED
ARCHIVED -> ACTIVE
```

Playbook Version 状态：

```text
DRAFT -> PUBLISHED -> DEPRECATED
  |          |
  +-> DELETED (仅未被引用草稿)
             +-> CLONED_AS_NEW_DRAFT
```

约束：

- `PUBLISHED` 不可修改，只能克隆成新草稿；
- Automation Rule 只能引用 `PUBLISHED` 版本；
- `DEPRECATED` 允许已有运行完成，但不能新绑定；
- Playbook 被 archive 时暂停其 Automation Rule，不删除历史；
- 同一 Playbook 同时最多一个可编辑草稿，使用乐观锁防覆盖。

Run 状态：

```text
QUEUED -> DISPATCHING -> RUNNING
RUNNING -> WAITING_APPROVAL -> RUNNING
RUNNING -> WAITING_INPUT    -> RUNNING
RUNNING -> CANCELLING -> CANCELLED
RUNNING -> SUCCEEDED | PARTIALLY_SUCCEEDED | FAILED | TIMED_OUT | ACTION_UNKNOWN
QUEUED/DISPATCHING -> FAILED | CANCELLED
```

`ACTION_UNKNOWN` 表示请求可能已经到达外部系统，但本地未取得可信回执。它不是 `FAILED`，不得自动重试不可逆动作。

Node Run 状态：

```text
PENDING -> READY -> RUNNING
RUNNING -> WAITING_APPROVAL | WAITING_INPUT
RUNNING -> SUCCEEDED | FAILED | SKIPPED | TIMED_OUT | ACTION_UNKNOWN
WAITING_* -> RUNNING | FAILED | SKIPPED
任意非终态 -> CANCELLED
SUCCEEDED -> COMPENSATING -> COMPENSATED | COMPENSATION_FAILED
```

## 6. 剧本定义

### 6.1 内部格式

内部格式使用版本化 JSON，而不是把 UI 布局当执行定义。发布时保存：

- `definition_json`：运行语义；
- `layout_json`：画布坐标、折叠和注释，只供 UI；
- `definition_hash`：规范化 JSON 的 SHA-256；
- `schema_version`：首版固定 `soar.playbook/v2`；
- 编译产物中的动作目录版本、风险摘要和静态引用。

示例：

```json
{
  "schemaVersion": "soar.playbook/v2",
  "entryNodeId": "start",
  "inputs": {
    "alert": { "type": "object", "required": true }
  },
  "limits": {
    "executionTimeout": "PT2H",
    "maxNodeExecutions": 500,
    "maxParallelism": 10
  },
  "nodes": [
    { "id": "start", "type": "START", "name": "开始" },
    {
      "id": "ti_lookup",
      "type": "ACTION",
      "name": "查询 IOC",
      "actionRef": "socp.threat-intel/ioc.lookup@1",
      "connectionRef": null,
      "parameters": {
        "value": { "$expr": "trigger.data.entity.value" }
      },
      "retryPolicy": {
        "maximumAttempts": 3,
        "initialInterval": "PT1S",
        "backoffCoefficient": 2.0,
        "maximumInterval": "PT10S"
      },
      "timeout": "PT30S",
      "onError": "FAIL_RUN"
    },
    {
      "id": "malicious",
      "type": "CONDITION",
      "name": "是否恶意",
      "expression": "nodes.ti_lookup.output.verdict in ['malicious', 'suspicious']"
    },
    {
      "id": "approval",
      "type": "APPROVAL",
      "name": "批准隔离终端",
      "policy": { "approvalsRequired": 1, "expiresIn": "PT30M" }
    },
    {
      "id": "isolate",
      "type": "ACTION",
      "name": "隔离终端",
      "actionRef": "endpoint/isolate-host@1",
      "connectionRef": "conn-edr-prod",
      "parameters": {
        "endpointId": { "$expr": "trigger.data.entity.endpointId" },
        "reason": "SOCP playbook ${run.id}"
      },
      "onError": "FAIL_RUN",
      "compensation": {
        "actionRef": "endpoint/release-host@1",
        "requiresApproval": true
      }
    },
    { "id": "end_ok", "type": "END", "outcome": "SUCCEEDED" },
    { "id": "end_clean", "type": "END", "outcome": "SUCCEEDED" }
  ],
  "edges": [
    { "from": "start", "to": "ti_lookup" },
    { "from": "ti_lookup", "to": "malicious" },
    { "from": "malicious", "port": "true", "to": "approval" },
    { "from": "malicious", "port": "false", "to": "end_clean" },
    { "from": "approval", "port": "approved", "to": "isolate" },
    { "from": "approval", "port": "rejected", "to": "end_clean" },
    { "from": "isolate", "to": "end_ok" }
  ]
}
```

文本模板中的 `${...}` 只允许引用已编译的数据路径，不能执行表达式；计算必须显式使用 `$expr`。

### 6.2 P0 节点类型

| 节点 | 用途 | 必要语义 |
|---|---|---|
| START | 唯一入口 | 恰好一个，无入边 |
| END | 分支或流程终点 | 至少一个，无出边，声明 outcome |
| ACTION | 调用目录中的动作 | schema 校验、超时、重试、回执、错误策略 |
| CONDITION | true/false 分支 | CEL 返回 boolean，两个端口完整 |
| SWITCH | 多值分支 | case 顺序明确，必须有 default |
| PARALLEL | 启动多个分支 | 受 `maxParallelism` 限制 |
| JOIN | 汇聚并行分支 | `ALL_SUCCESS`、`ALL_DONE`、`ANY_SUCCESS` |
| FOREACH | 遍历数组 | 最大 100 项；并发 1~10；每项独立 iteration |
| DELAY | 等待一段时间或时间点 | 使用 Temporal Timer，不阻塞线程 |
| APPROVAL | 等待批准/拒绝/过期 | Signal 驱动，支持策略分支 |
| MANUAL_TASK | 结构化人工输入 | 表单 JSON Schema、负责人、到期时间 |
| SUB_PLAYBOOK | 调用已发布子剧本 | Child Workflow；发布时固定版本 |
| SET_VARIABLE | 构造非敏感变量 | 只能写 `vars.*`，输出经 schema 限制 |

P0 不允许任意回边。循环只能通过结构化 `FOREACH` 表达；P1 的 `WHILE` 也必须有 `maxIterations` 和总超时。

### 6.3 表达式与数据上下文

采用 CEL 作为条件和计算表达式。原因是它可预编译、可类型检查、非通用脚本语言，适合处理不可信安全事件。严禁用 SpEL 或脚本引擎解释用户内容。

上下文命名空间固定为：

| 命名空间 | 内容 | 可写性 |
|---|---|---|
| `trigger` | 触发事件不可变快照 | 只读 |
| `run` | runId、时间、发起者、触发类型 | 只读 |
| `vars` | 剧本声明的工作变量 | `SET_VARIABLE` 可写 |
| `nodes.<nodeId>.output` | 已完成节点的脱敏输出 | 只读 |
| `iteration` | foreach 的 index/item | 只读、仅循环体 |
| `case`、`alert` | 可选的规范化业务对象 | 只读；修改必须走 Action |

不存在可被剧本读取的 `secrets` 命名空间。密钥只在 Action Activity 内按引用解析。

表达式限制：长度 4 KiB、编译 100 ms、执行 50 ms、最大嵌套 20、集合最大 1,000 项；不开放反射、文件、网络、当前时间或随机函数。所有表达式在发布前编译，在运行时只执行编译结果对应的规范化表达式。

### 6.4 发布前静态校验

发布接口必须一次返回全部 `errors[]` 和 `warnings[]`，每项包含 `code`、`nodeId`、`path`、`message`。至少校验：

1. schema 版本、定义大小（最大 256 KiB）和节点数（最大 200）；
2. node ID 唯一且符合 `[a-zA-Z][a-zA-Z0-9_-]{0,63}`；
3. 唯一 START、至少一个 END、端口合法、所有节点可达；
4. 除结构化循环外无环，PARALLEL/JOIN 成对且不会永久等待；
5. `actionRef` 存在、版本可用，参数与输出映射符合 JSON Schema；
6. `connectionRef` 属于当前租户且连接器类型匹配；
7. 数据引用只指向必然先完成的节点；并行分支不能读取未 join 的结果；
8. 子剧本存在、已发布且调用图无递归；最大深度 5；
9. foreach、并发、节点数、超时和数据大小有界；
10. 高风险动作前存在满足策略的审批节点；补偿动作同样经过策略；
11. 所有失败、拒绝、过期和 default 路径有确定终点；
12. 未出现 secret 值、内嵌凭据 URL、明文 token 或被禁地址。

校验通过不代表连接可用；发布结果需同时显示连接健康状态和最后测试时间。

### 6.5 模板与内容包

P0 随仓库交付第 17.5 节的五个黄金场景模板。模板以版本化 JSON 资源保存，不在应用启动时用临时代码硬编码；每个模板包含说明、适用事件类型、所需连接器、预期输入、风险、ATT&CK 标签和测试样例。安装模板只会创建租户草稿，绝不自动发布、启用规则或执行动作。

内部 JSON 导入必须经过与 UI 发布相同的 schema、引用、风险和权限校验，不能成为绕过入口。P1 增加 CACAO 2.0 映射：能无损映射的核心节点直接转换，不能映射的扩展保留在 namespaced extension 中并阻止自动发布；导入报告逐项说明降级或不支持内容。

## 7. 自动化规则与触发

### 7.1 事件信封

SOAR 不再为每种来源接受任意 Map。所有自动触发先规范化为：

```json
{
  "schemaVersion": "soar.event/v1",
  "eventId": "alert:uuid:created:1",
  "eventType": "alert.created",
  "tenantId": "tenant-a",
  "occurredAt": "2026-09-03T08:00:00Z",
  "producer": "alert-web",
  "subject": { "type": "alert", "id": "uuid" },
  "data": {
    "alert": {},
    "entities": [],
    "evidence": []
  },
  "trace": {
    "traceparent": "...",
    "correlationId": "...",
    "causationId": "...",
    "automationDepth": 0
  }
}
```

P0 事件类型：

- `alert.created`、`alert.updated`；
- `incident.created`、`incident.updated`；
- `entity.selected`；
- `schedule.fired`；
- `webhook.received`；
- `manual.requested`。

`webhook.received` 必须绑定启用的 Webhook Connection，使用 HMAC/非对称签名和时间戳防重放，并以 `(tenant_id, connection_id, external_event_id)` 去重；租户不能由 body 或普通 `X-Tenant-Id` 自报。`entity.selected` 是经过用户鉴权的手工入口，主体类型至少支持 IP、domain、URL、file hash、user、host 和 endpoint。

当前 Alert payload 中已有 `riskScore`、`riskLevel`、`triggerEventId` 和 `evidence`，新入口必须保留它们，不能再被 `AlarmEvaluationRequest` 静默丢弃。Incident Web 需要增加 transactional outbox，发出案件 created/updated 事件。

### 7.2 Automation Rule

规则包含：

- `eventType`；
- CEL `condition`；
- `priority`，数字小的先评估；
- 固定的 `playbookVersionId`；
- `enabled` 和生效时间窗；
- `dedupWindow`、`cooldown`；
- `groupBy` 表达式；
- `maxConcurrentRuns`；
- 冲突策略 `QUEUE` 或 `SUPPRESS`；
- 可选标签、描述和 owner。

一个事件可命中多个规则。评估顺序稳定为 `(priority, rule_id)`；每条命中独立产生 Run。规则之间不共享可变上下文。

### 7.3 幂等、抑制和回环防护

- Trigger Receipt 唯一键为 `(tenant_id, event_id, automation_rule_id, rule_revision)`；
- Run 的 Workflow ID 固定为 `soar/{tenantHash}/{runId}`；重复 start 视为对同一运行的确认；
- Action 幂等键固定为 `sha256(tenantId, executionSeriesId, nodeId, iterationPath, logicalTarget)`，同一逻辑重试保持不变；
- 自动化产生的新事件必须携带 `causationId` 和 `automationDepth + 1`；默认不匹配由同一 Run 产生的相同事件；
- `automationDepth > 5` 直接抑制并审计；
- cooldown/groupBy 命中时创建 `SUPPRESSED` receipt，而不是静默丢弃；
- `QUEUE` 有租户和连接两层上限，不能无限堆积。

### 7.4 接收与调度一致性

接收路径采用本地事务 + Outbox：

```mermaid
sequenceDiagram
    participant A as Alert/Incident Web
    participant S as SOAR API
    participant DB as SOAR PostgreSQL
    participant D as Dispatch Worker
    participant T as Temporal
    A->>S: signed event envelope
    S->>DB: receipt + run + dispatch_outbox (one transaction)
    S-->>A: 202 receipt/run ids
    D->>DB: claim pending dispatch
    D->>T: start workflow with stable workflowId
    T-->>D: started or already exists
    D->>DB: mark dispatched
```

只要 API 返回 `202`，receipt、run 和 dispatch intent 就必须已经提交。Temporal 暂时不可用时保留 `QUEUED` 并重试，不得改走进程内执行器。超过重试预算进入 `DEAD`，在运维 API 和指标中可见，可人工 requeue/discard；discard 必须填写原因。

## 8. 执行引擎

### 8.1 总体架构

首版不新增独立部署服务，保持 `soar-web` 模块和现有 `response-integration` 目标单元，但在代码内严格分层：

```mermaid
flowchart LR
  API[Control/Trigger API] --> PG[(PostgreSQL)]
  API --> OUT[Dispatch Outbox]
  OUT --> TC[Temporal Client]
  TC --> WF[Generic Playbook Workflow]
  WF --> PA[Projection Activities]
  WF --> AA[Action Activities]
  WF --> SIG[Approval/Input/Cancel Signals]
  AA --> REG[Connector Registry]
  REG --> CORE[SOCP Service Clients]
  REG --> EXT[External Products]
  PA --> PG
  PG --> SSE[Run Event SSE]
  SSE --> UI[Workbench]
```

建议包结构：

```text
com.socp.soar.web
├── api                  # controller + request/response DTO
├── application          # use cases / transaction boundaries
├── domain               # aggregates, enums, policies
├── definition           # schema, compiler, validator, CEL
├── automation           # event normalization, rule evaluation, receipts
├── connector
│   ├── api              # SDK contracts and descriptors
│   ├── builtin          # SOCP native connectors
│   └── external         # controlled HTTP/vendor adapters
├── runtime              # dispatcher, projections, idempotency, redaction
├── temporal.v2          # workflow, activities, signals, DTOs
└── persistence          # JPA entities/repositories/stores
```

`PlaybookExecutor`、`PlaybookActionType` 和旧 `PlaybookWorkflowImpl` 只作为兼容层存在，不能继续承载 V2 语义。

### 8.2 Temporal 运行契约

1. API 使用 `WorkflowClient.start(...)` 异步启动，禁止同步 `stub.executePlaybook(...)`。
2. Workflow 输入只包含不可变定义快照、非敏感触发快照和 ID；总大小上限 512 KiB。
3. Workflow 代码只做确定性图推进，不访问数据库、网络、系统时钟、随机数或 Spring Bean；所有 I/O 是 Activity。
4. 审批、人工输入和取消使用 Signal；查询主要读 PostgreSQL 投影，不让 UI 依赖 Temporal 可用性。
5. 每个 Action 是独立 Activity；Projection 更新可重试且幂等。
6. Delay 使用 Temporal Timer，Sub-playbook 使用 Child Workflow。
7. 长循环每 250 个节点或 History 达到内部阈值时 Continue-As-New，并携带最小状态。
8. Activity 必须配置 Start-to-Close、Schedule-to-Close、Heartbeat（长动作）和显式 Retry Policy。
9. Workflow/Activity DTO 只做加字段兼容；破坏性变更使用新 Workflow type/task queue 或 Temporal Worker Deployment 版本策略。
10. 生产 Temporal 不可用时 health 为 `DEGRADED`、执行排队；不能伪装成功或内存降级。

### 8.3 图执行语义

- START 产生一个 token；普通节点完成后按端口把 token 发送到后继节点；
- CONDITION/SWITCH 每次只选择一个输出端口；
- PARALLEL 为每个分支产生 token；JOIN 按固定策略收敛；
- `ALL_SUCCESS` 遇到失败按 JOIN 的 `onError` 处理，`ALL_DONE` 输出各分支结果摘要；
- FOREACH 为每项建立 `iterationPath`，输出按原输入顺序聚合，不按完成顺序；
- 同一节点在同一 iteration 中最多执行一次，除非进入显式补偿；
- 节点输出不可覆盖；`vars` 更新由 Workflow 单线程确定性合并；
- 到达 END 后只有所有活动分支均终止，Run 才进入终态；
- 达到 `maxNodeExecutions`、总超时或取消请求时停止调度新节点。

### 8.4 错误、重试与不确定结果

每个动作声明：

- `sideEffect`: `NONE | REVERSIBLE | IRREVERSIBLE`；
- `idempotency`: `NATIVE | RECONCILABLE | NONE`；
- `riskLevel`: `READ_ONLY | LOW | MEDIUM | HIGH | CRITICAL`；
- 可重试错误码集合；
- 默认超时与最大尝试；
- 可选 `reconcile` 和 `compensate` 能力。

策略：

| 情况 | 行为 |
|---|---|
| 调用前校验失败 | `FAILED`，不重试 |
| 明确 4xx 业务拒绝 | `FAILED`，不重试 |
| 429/503/连接前失败且动作可幂等 | 指数退避 + jitter |
| 请求发出后超时，动作支持查询 | 先 `reconcile(idempotencyKey/operationId)`，再决定重试 |
| 请求发出后超时，动作不可查询且不可幂等 | `ACTION_UNKNOWN`，等待人工处置 |
| 取得 accepted 但异步未完成 | 按 operationId 轮询/回调，保持 RUNNING |
| 补偿失败 | `COMPENSATION_FAILED`，Run 为 `PARTIALLY_SUCCEEDED` 或 `FAILED` |

不得用 Temporal 默认无限 Activity 重试。通知、建案等可能重复的动作也必须依赖业务幂等 receipt，而不是假定 HTTP 客户端重试安全。

节点 `onError` 仅允许：

- `FAIL_RUN`；
- `CONTINUE`（发布时产生高可见 warning）；
- `GOTO_ERROR_PORT`；
- `COMPENSATE_THEN_FAIL`。

### 8.5 手工 retry 与 rerun

- `retry`：创建新 Run，但沿用 `executionSeriesId` 和已确认动作的幂等键，只从安全检查通过的失败节点继续；原 Run 永不改写。
- `rerun`：全新 `executionSeriesId`，表示用户明确要求再次产生副作用；UI 必须展示风险摘要并二次确认。
- `ACTION_UNKNOWN` 只能先由具备权限的操作员标记 `CONFIRMED_SUCCEEDED` 或 `CONFIRMED_NOT_EXECUTED`，附证据和原因，之后才能继续。

## 9. Connector SDK 与动作目录

### 9.1 分层模型

Connector Definition 是代码发布的能力描述；Connection 是租户配置；Action Definition 是可拖入剧本的强类型动作。剧本只能引用 `actionRef + connectionRef`，不允许把任意 URL、用户名或 token 写入动作字符串。

Connector SDK 最小契约：

```java
public interface SoarConnector {
    ConnectorDescriptor descriptor();
    ConnectionTestResult test(ConnectionContext connection);
    ActionResult execute(ActionRequest request);
    default Optional<ActionResult> reconcile(ActionQuery query) { return Optional.empty(); }
    default Optional<ActionResult> compensate(ActionRequest request) { return Optional.empty(); }
}
```

`ActionRequest` 包含 tenant、run/node/attempt ID、稳定幂等键、已通过 schema 校验的参数、目标快照和仅 Activity 内可见的 credential handle。`ActionResult` 必须包含标准状态、operationId/receipt、脱敏输出、可重试标志、错误码和远端时间；不得返回 credential。

### 9.2 Action Definition 元数据

每个动作必须声明：

- 稳定 ID 和 major version，例如 `endpoint/isolate-host@1`；
- 本地化名称、描述、分类和图标；
- input/output JSON Schema 和 UI hints；
- 是否需要 Connection、支持的连接器类型；
- sideEffect、riskLevel、idempotency 和审批默认值；
- 默认/最大超时、重试上限、并发和速率提示；
- 输出中的 sensitive 字段和最大 payload；
- 可用的 compensate/reconcile 动作；
- 所需平台权限和允许目标类型。

动作 schema 的 breaking change 必须升 major；已发布剧本继续解析旧 major，直到版本被明确下线。

### 9.3 P0 动作目录

| Connector | P0 动作 | 备注 |
|---|---|---|
| `socp.alert` | get、add-note、assign、set-status、add-tag | 缺失的 tag API 在 Alert Web 补齐 |
| `socp.incident` | get/create、append-timeline、assign、set-status、add-task/complete-task | Incident Web 补齐任务与幂等写 API |
| `socp.search` | search-events、get-event | 只读，限制时间窗和返回条数 |
| `socp.asset` | find-by-entity、get-asset | 只读富化 |
| `socp.threat-intel` | lookup-ioc | 只读富化 |
| `socp.notify` | send-channel | 显式 channelId 和 delivery receipt |
| `http.webhook` | request | 仅管理员创建的 Connection，受出站策略约束 |
| `endpoint` | isolate-host、release-host、snapshot | 通过真实厂商适配器；未配置时明确不可用 |
| `firewall` | block-ioc、unblock-ioc | 通过真实厂商适配器；要求 operation receipt |

不能把模拟 endpoint/firewall 行为算作 P0 生产验收。仓库可以提供 WireMock/reference adapter 供测试，但 UI 必须显示 `TEST ONLY`。

### 9.4 Connection 与密钥

Connection 保存：名称、connectorId、非敏感 `config_json`、`secret_refs_json`、作用域、标签、状态、版本和最后测试结果。

密钥策略：

1. 数据库只存引用，例如 `env://SOCP_EDR_CLIENT_SECRET` 或 `k8s://namespace/secret/key`，不存值。
2. 首版实现 `SecretResolver` SPI 和环境变量 resolver；生产推荐由 Kubernetes Secret 注入环境变量。后续可增加 Vault/KMS provider。
3. 解析只发生在 Action Activity 内；Workflow、API 和校验器只能看到引用和掩码。
4. test connection 也走审计、超时、出站策略和脱敏。
5. 运行记录保存 `connectionId + connectionRevision`，但密钥轮换无需重发剧本版本。
6. 删除 Connection 必须是软删除；被发布版本引用时拒绝删除。

### 9.5 通用 HTTP 安全策略

- endpoint 只能由 `soar:connections:manage` 权限配置，剧本作者不能覆盖 host/scheme/port；
- 只允许 HTTPS；开发 profile 可显式允许 loopback 测试；
- DNS 解析前后都执行 SSRF 校验，禁止 link-local、云 metadata、未授权私网和重绑定；
- 私有 SOCP 服务不走通用 HTTP，而走类型化 service client；
- 配置域名/网段 allowlist、允许 method、最大请求/响应 1 MiB、连接/读取超时；
- 禁止 URL 内嵌 credential，禁止任意 hop 转发平台 Authorization/Cookie；
- 默认不跟随重定向；如开启，每一跳重新校验；
- TLS 校验不可关闭，敏感 header 只从 secret resolver 注入且永不回显。

## 10. 风险与审批策略

### 10.1 默认策略

| 风险 | 示例 | 自动执行默认值 | 审批要求 |
|---|---|---|---|
| READ_ONLY | IOC/资产/事件查询 | 允许 | 无 |
| LOW | 添加 note/tag、创建人工任务 | 允许 | 无 |
| MEDIUM | 通知、建案、分派、改案件状态 | 租户管理员可配置 | 可选 |
| HIGH | 隔离/释放终端、封禁/解封 IOC、快照 | 禁止 | 1 名非发起人 |
| CRITICAL | 禁用账号、删除资源、大范围封禁 | 禁止 | P0 禁用；P1 至少 2 名非发起人 |

策略取 `max(action 默认风险, connection 风险, target 范围风险, tenant policy)`，剧本作者只能提高不能降低。批量目标超过阈值自动升级风险。

### 10.2 审批规则

- 审批绑定 `runId + nodeRunId + inputHash + targetSnapshot + expiresAt`；参数变化后旧审批失效；
- 发起者、最近一次编辑者不能审批自己的高风险动作；
- 只有策略指定角色/用户组可审批；
- `policy.allowedRoles` 与 `policy.allowedGroups`（也兼容 `approverRoles`/`approverGroups`）在发布时冻结到 `policy_json`；审批服务从当前认证主体的 authorities/常见 OIDC group claims 取交集，指定名单但无法取得主体属性时必须拒绝（fail closed）；名单为空才表示允许所有具有 `soar:approve` 的非发起人；
- Approve/Reject 使用数据库条件更新和唯一 decision，处理双击与并发；
- 达到票数后在同一事务更新 Approval 状态并写 signal outbox；dispatcher 向 Temporal 发 Signal；
- 过期和拒绝都必须走定义中的明确端口；没有端口则失败；
- UI 显示动作、目标、连接、参数 diff、风险来源、请求人、到期时间和剧本版本；
- 不提供未经审计的“强制通过”。Break-glass 若未来实现，必须是独立权限、强认证和不可删除审计事件。

## 11. 持久化设计

生产以 PostgreSQL 为语义基准，使用 `JSONB`、唯一约束、条件更新和 `FOR UPDATE SKIP LOCKED`。H2 只保留开发/单元测试兼容，不作为并发正确性证明。

### 11.1 表清单

| 表 | 关键字段 | 关键约束/索引 |
|---|---|---|
| `t_soar_playbook` | id, tenant_id, name, description, owner, tags, status, latest_published_version, row_version, timestamps | `(tenant_id, lower(name))` 唯一；tenant/status 索引 |
| `t_soar_playbook_version` | id, tenant_id, playbook_id, version_no, status, schema_version, definition_json, layout_json, definition_hash, risk_summary_json, created/published_by, timestamps, row_version | `(tenant_id, playbook_id, version_no)` 唯一；发布 hash 固定 |
| `t_soar_automation_rule` | id, tenant_id, revision, event_type, condition, priority, playbook_version_id, limits_json, enabled, row_version | tenant/event/enabled/priority 索引 |
| `t_soar_connection` | id, tenant_id, connector_id, name, config_json, secret_refs_json, scope_json, revision, status, test fields, deleted_at | `(tenant_id, lower(name))` 唯一（未删除） |
| `t_soar_trigger_receipt` | id, tenant_id, event_id, rule_id, rule_revision, status, run_id, reason, timestamps | `(tenant_id,event_id,rule_id,rule_revision)` 唯一 |
| `t_soar_dispatch_outbox` | id, tenant_id, run_id, status, attempts, next_attempt_at, claimed_by/at, last_error | status/next_attempt 索引；run_id 唯一 |
| `t_soar_run` | id, tenant_id, execution_series_id, playbook/version/hash, trigger fields, status, temporal_workflow_id, temporal_run_id, input_ref, counters, error, requester, timestamps, row_version | tenant/status/created、subject、series 索引 |
| `t_soar_node_run` | id, tenant_id, run_id, node_id, iteration_path, type, status, input_ref, output_ref, action/connection revision, idempotency_key, error, timestamps, row_version | `(tenant_id,run_id,node_id,iteration_path)` 唯一；idempotency_key 索引 |
| `t_soar_action_attempt` | id, tenant_id, node_run_id, attempt_no, status, request_hash, remote_operation_id, receipt_json, error_code, retryable, timestamps | `(node_run_id,attempt_no)` 唯一 |
| `t_soar_approval` | id, tenant_id, run/node IDs, policy_json, input_hash, target_json, status, required_count, expires_at, requester, timestamps, row_version | tenant/status/expires 索引 |
| `t_soar_approval_decision` | id, tenant_id, approval_id, actor_id, decision, reason, created_at | `(approval_id,actor_id)` 唯一 |
| `t_soar_manual_task` | id, tenant_id, run/node IDs, form_schema, input_json, assignee, status, due_at, completed_by/at, row_version | tenant/status/assignee/due 索引 |
| `t_soar_signal_outbox` | id, tenant_id, run_id, signal_type, signal_key, payload_json, status, attempts, claim fields | status/next_attempt；按 gate/node 的业务信号唯一键 |
| `t_soar_run_event` | sequence_id, event_id, tenant_id, run_id, node_run_id, type, actor, summary, detail_json, trace_id, ts | `(tenant_id,run_id,sequence_id)`；event_id 唯一 |
| `t_soar_artifact` | id, tenant_id, run_id, node_run_id, media_type, size, sha256, storage_ref, classification, expires_at | tenant/run、expires 索引 |

所有表都必须有 `tenant_id NOT NULL`。所有外键在应用查询中同时校验 tenant；PostgreSQL RLS 覆盖新表。历史表不级联物理删除。

### 11.2 Payload 与保留

- Run/Node 表只保存摘要和 artifact reference；单个 inline JSON 最大 64 KiB；
- 64 KiB~10 MiB 输出进入 artifact 存储；超过 10 MiB 失败，除非动作另有流式协议；
- artifact 记录 SHA-256、media type、classification 和 retention；下载再次鉴权；
- 默认 Run/Node/Attempt/Event 保留 180 天，审计至少 365 天，artifact 30 天；均可按租户提高；
- 删除剧本不删除运行证据；敏感输出按分类提前清理时保留 hash 和清理审计；
- `definition_json` 和触发快照在存储前经过 secret/PII redaction policy。

### 11.3 Flyway 迁移建议

现有版本到 `V5__playbook_approval.sql`，新实现按可回滚的功能边界追加：

1. `V6__soar_playbook_versioning.sql`；
2. `V7__soar_automation_and_dispatch.sql`；
3. `V8__soar_run_projection.sql`；
4. `V9__soar_connector_connections.sql`；
5. `V10__soar_human_tasks.sql`；
6. `V11__soar_artifacts_and_retention.sql`。

不要修改已发布 V1~V5。迁移测试必须同时在 H2 和 PostgreSQL 执行，锁、唯一 claim 和并发审批只以 PostgreSQL 测试为准。

## 12. HTTP API 设计

### 12.1 通用约束

- 保持服务 context path `/soar-web`。由于现有 `/api/v1/playbooks` 的 list/create/get shape 与同步执行语义会发生 breaking change，SOAR 2.0 控制面统一使用 `/api/v2`；既有 `/api/v1` 只作为迁移期兼容面；
- 所有 JSON 响应使用 `ApiResult<T>`；
- 列表统一 `{page,size,total,items}`，`size` 最大 200；
- 创建返回 `201`；异步触发返回 `202`；乐观锁冲突返回 `409`；
- 更新使用 `If-Match`/`rowVersion`；重复幂等请求返回原资源；
- ID 使用 UUID/UUIDv7，不使用短随机 ID；
- OpenAPI 明确权限、错误、分页、状态 enum 和示例；
- 错误响应不泄露表达式堆栈、凭据、内部地址或远端原始 body。

手工运行请求与接受响应的最小契约：

```http
POST /soar-web/api/v2/runs
{
  "requestId": "client-generated-uuid",
  "playbookVersionId": "published-version-uuid",
  "subject": { "type": "alert", "id": "alert-uuid" },
  "inputs": { "reason": "analyst requested enrichment" }
}
```

```http
HTTP/1.1 202 Accepted
{
  "code": 0,
  "message": "ok",
  "data": {
    "runId": "run-uuid",
    "status": "QUEUED",
    "duplicate": false,
    "links": { "self": "/soar-web/api/v2/runs/run-uuid" }
  },
  "traceId": "...",
  "timestamp": "2026-09-03T08:00:00Z"
}
```

同一租户重复提交相同 `requestId` 必须返回同一个 Run 并令 `duplicate=true`。Actor、tenant 和权限一律来自认证上下文，不能接受 body 覆盖。

### 12.2 Playbook 与版本

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v2/playbooks` | 分页搜索，按 status/tag/owner/risk 过滤 |
| POST | `/api/v2/playbooks` | 创建元数据和空草稿 |
| GET | `/api/v2/playbooks/{id}` | 元数据、最新发布版、草稿摘要 |
| PATCH | `/api/v2/playbooks/{id}` | 更新名称、描述、owner、tag、archive |
| POST | `/api/v2/playbooks/{id}/drafts` | 从空白或指定版本克隆草稿 |
| GET | `/api/v2/playbooks/{id}/versions` | 版本列表 |
| GET | `/api/v2/playbooks/{id}/versions/{version}` | 定义和 layout |
| PUT | `/api/v2/playbooks/{id}/versions/{version}` | 保存草稿，乐观锁 |
| POST | `/api/v2/playbooks/{id}/versions/{version}/validate` | 静态校验、风险和连接检查 |
| POST | `/api/v2/playbooks/{id}/versions/{version}/publish` | 冻结并发布 |
| POST | `/api/v2/playbooks/{id}/versions/{version}/deprecate` | 禁止新绑定 |
| POST | `/api/v2/playbooks/import` | 导入内部 JSON 草稿 |
| GET | `/api/v2/playbooks/{id}/versions/{version}/export` | 导出内部 JSON；P1 支持 CACAO |

### 12.3 Automation Rule

| Method | Path | 用途 |
|---|---|---|
| GET/POST | `/api/v2/automation-rules` | 分页列表/创建 |
| GET/PATCH | `/api/v2/automation-rules/{id}` | 查看/更新并增加 revision |
| POST | `/api/v2/automation-rules/{id}/enable` | 启用前重新校验引用和连接 |
| POST | `/api/v2/automation-rules/{id}/disable` | 停用 |
| POST | `/api/v2/automation-rules/test` | 用样例事件解释匹配结果，不执行 |
| POST | `/api/v2/events/evaluate` | 服务身份事件入口，返回 receipts/runs |

旧 `POST /api/v1/playbooks/evaluate` 在兼容期转换成 `alert.created` 后调用同一 application service，不允许维护第二套匹配器。

### 12.4 Run、节点和人工交互

| Method | Path | 用途 |
|---|---|---|
| POST | `/api/v2/runs` | 手工执行已发布版本，返回 202 |
| GET | `/api/v2/runs` | 按状态、剧本、来源、时间、发起人分页筛选 |
| GET | `/api/v2/runs/{id}` | Run 摘要及当前等待项 |
| GET | `/api/v2/runs/{id}/nodes` | 节点投影和 attempts |
| GET | `/api/v2/runs/{id}/events` | 游标分页运行事件 |
| GET | `/api/v2/runs/{id}/stream` | SSE；支持 `Last-Event-ID` 续传 |
| GET | `/api/v2/runs/{id}/artifacts` | 运行产物元数据列表（按分类和保留策略返回） |
| POST | `/api/v2/runs/{id}/artifacts` | 上传受限 JSON 产物；绑定节点、媒体类型和数据分类，返回 201 |
| GET | `/api/v2/artifacts/{id}/content` | 读取经过脱敏和权限检查的产物内容 |
| POST | `/api/v2/runs/{id}/cancel` | 请求协作式取消，要求 reason |
| POST | `/api/v2/runs/{id}/retry` | 安全 retry，先返回可续跑分析 |
| POST | `/api/v2/runs/{id}/rerun` | 明确的新副作用执行，要求确认和 reason |
| POST | `/api/v2/node-runs/{id}/resolve-unknown` | 提交外部证据并确认未知动作结果 |
| GET | `/api/v2/approvals` | 待我审批/全部审批分页列表 |
| POST | `/api/v2/approvals/{id}/decisions` | approve/reject，幂等 decision |
| GET | `/api/v2/manual-tasks` | 待办分页列表 |
| POST | `/api/v2/manual-tasks/{id}/complete` | 按 form schema 提交输入 |

### 12.5 Connector 与运维

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v2/connectors` | 连接器和动作目录 |
| GET | `/api/v2/actions` | 按分类、风险、输入类型搜索动作 |
| GET/POST | `/api/v2/connections` | 租户连接列表/创建 |
| GET/PATCH/DELETE | `/api/v2/connections/{id}` | 查看、更新、软删除 |
| POST | `/api/v2/connections/{id}/test` | 连通性与权限测试 |
| GET | `/api/v2/operations/dead-dispatches` | DEAD dispatch/signal 列表 |
| POST | `/api/v2/operations/dead-dispatches/{id}/requeue` | 审计后重放 |
| POST | `/api/v2/operations/dead-dispatches/{id}/discard` | 填原因后丢弃 |
| GET | `/api/v2/stats` | 运行、成功率、时延、自动化节省摘要 |

### 12.6 稳定错误码

至少提供：`SOAR_PLAYBOOK_NOT_FOUND`、`SOAR_VERSION_CONFLICT`、`SOAR_DEFINITION_INVALID`、`SOAR_VERSION_IMMUTABLE`、`SOAR_ACTION_NOT_FOUND`、`SOAR_CONNECTION_UNAVAILABLE`、`SOAR_TRIGGER_DUPLICATE`、`SOAR_RUN_NOT_CANCELLABLE`、`SOAR_RUN_NOT_RESUMABLE`、`SOAR_APPROVAL_EXPIRED`、`SOAR_SELF_APPROVAL_DENIED`、`SOAR_ACTION_RESULT_UNKNOWN`、`SOAR_SECRET_RESOLUTION_FAILED`、`SOAR_EGRESS_DENIED`、`SOAR_CAPACITY_EXCEEDED`。

## 13. 权限、安全与审计

### 13.1 权限

在 `platform/socp-auth` 增加：

| 权限 | 用途 | 默认角色建议 |
|---|---|---|
| `soar:view` | 查看剧本、运行、目录 | viewer/analyst/admin |
| `soar:edit` | 编辑草稿和规则草稿 | analyst/admin |
| `soar:publish` | 发布/弃用版本、启用规则 | admin 或专门 publisher |
| `soar:execute` | 手工执行、取消 | analyst/admin |
| `soar:approve` | 审批分配给自己的动作 | approver/admin |
| `soar:task:complete` | 完成人工任务 | analyst/admin |
| `soar:connections:view` | 查看掩码后的连接 | analyst/admin |
| `soar:connections:manage` | 创建/修改/测试连接 | admin |
| `soar:operations` | requeue/discard/resolve unknown | admin/operations |

`soar:approve` 不能自动绕过自批限制；gateway 检查只是纵深防御，`soar-web` 自己必须再次鉴权。

### 13.2 发布与执行安全

- 草稿作者和发布者默认职责分离；生产租户可强制 `author != publisher`；
- 发布记录 definition hash、风险摘要、动作/连接版本和 actor；
- 手工运行只能运行 Published 版本；草稿只允许 `dry-run`，且所有 side-effect action 被 stub；
- dry-run 结果必须是 `SIMULATED`，不能写 `SUCCEEDED`；生产不能把 stub 当真实动作；
- 输入事件、远端响应和人工输入均视为不可信数据；只做数据绑定，不能改变控制逻辑；
- 所有目标在审批前解析并冻结摘要；执行前再次校验 target scope 和 tenant；
- 对每租户、连接、动作和目标设置并发/速率限制；限流等待可见，不直接丢失。

### 13.3 审计事件

以下操作全部通过 `@AuditOperation` 或显式 `AuditSink` 记录成功和失败：

- playbook create/update/archive，draft save/delete，validate/publish/deprecate/import/export；
- automation rule create/update/enable/disable/test；
- connection create/update/test/delete；
- manual run/cancel/retry/rerun；
- approval request/approve/reject/expire，manual task complete；
- action dispatch/result/reconcile/compensate/resolve unknown；
- dead outbox requeue/discard、retention purge。

审计详情包含 tenant、actor/service identity、目标、run/node/version/hash、结果、traceId 和 reason；只记录参数 hash 与脱敏摘要，不记录 token、密码、cookie、完整 evidence 或远端敏感 body。

## 14. 前端工作台

现有单页 `SoarView.vue` 拆为“自动化”工作区，至少包含五个一级页签。

### 14.1 剧本

- 卡片/表格切换，展示 owner、标签、最新版本、引用规则数、风险、最近成功率；
- 创建、复制、archive、导入、导出；
- 打开后进入可视化编辑器。

编辑器建议使用固定版本的 `@vue-flow/core`，但执行定义不得依赖其内部 node/edge 格式。界面布局：

```text
┌──────────────┬──────────────────────────────────┬─────────────────────┐
│ 节点/动作面板 │         画布 + minimap           │ 节点配置/数据选择器   │
│ 搜索/分类     │   校验标记、分支端口、风险标识    │ schema 表单/重试/错误 │
├──────────────┴──────────────────────────────────┴─────────────────────┤
│ errors / warnings / 连接健康 / 版本与保存状态 / 测试结果             │
└───────────────────────────────────────────────────────────────────────┘
```

必须具备：拖放、连线、缩放、小地图、自动布局、撤销/重做、复制粘贴、键盘删除、未保存提示、乐观锁冲突处理、节点搜索、数据路径选择器、schema 动态表单、实时但防抖的本地校验、服务端 Validate、dry-run 和发布风险确认。可访问性要求键盘可操作且颜色不是唯一状态信号。

### 14.2 自动化规则

- 事件类型、条件 builder/CEL 高级模式、优先级、版本、groupBy、cooldown、并发策略；
- “用样例事件测试”显示每个条件的解释和最终是否命中；
- 启停、最近命中时间、命中/抑制/失败统计；
- 不能选择草稿或连接不满足策略的版本。

### 14.3 运行

- 分页过滤运行；状态、来源、剧本版本、耗时、等待项和失败节点可见；
- 详情以图和时间线联动展示；节点面板显示脱敏输入、输出、attempt、receipt、traceId；
- SSE 更新，断线后按 event ID 续传，SSE 不可用时降级轮询；
- cancel/retry/rerun/resolve unknown 入口按状态和权限显示；
- 外部动作链接只能跳到 connector 返回且通过 allowlist 的 operation URL。

### 14.4 审批与人工任务

- 默认“待我处理”，支持风险/到期时间/剧本筛选；
- 审批抽屉显示目标快照、参数、风险原因、前置调查输出、请求人和审计历史；
- approve/reject 必须填写原因（可配置最小长度）；
- Manual Task 用 JSON Schema 表单渲染，提交后不可静默覆盖。

### 14.5 连接

- 只向有权限用户展示；普通分析员最多看到名称、类型和健康状态；
- 配置表单区分普通字段和 secret reference，永不回显 secret；
- 显示最后测试 actor/time/result、引用剧本数、动作能力、scope 和健康；
- 危险修改说明受影响的已发布版本，保存需要确认。

## 15. 可观测性与运维

### 15.1 指标

至少输出以下 Prometheus 指标，标签不得包含 tenantId、runId 或 entity 等高基数字段：

- `soar_trigger_received_total{type,result}`；
- `soar_trigger_evaluation_duration_seconds{type}`；
- `soar_dispatch_total{result}`、`soar_dispatch_backlog`、`soar_dispatch_oldest_age_seconds`；
- `soar_runs_total{trigger,status}`、`soar_runs_active{state}`；
- `soar_run_duration_seconds{trigger,status}`；
- `soar_node_duration_seconds{node_type,connector,action,status}`；
- `soar_action_attempts_total{connector,action,result}`；
- `soar_action_unknown_total{connector,action}`；
- `soar_approvals_active{risk}`、`soar_approval_wait_seconds{decision}`；
- `soar_connector_health{connector}`、`soar_connector_rate_limited_total{connector}`；
- `soar_signal_backlog`、`soar_dead_outbox_total{kind}`；
- `soar_redactions_total{source}`。

### 15.2 日志与追踪

- 日志字段：traceId、tenant 的不可逆 hash、runId、nodeId、attemptId、playbookVersion、connector/action、status、durationMs、errorCode；
- 不记录完整 input/output、Authorization、Cookie、secret ref 解析值；
- 从 Alert/Incident event 继续 W3C trace；每个 Action Activity 建 span，并把远端 request ID 作为 attribute；
- Run Event 是用户可见事实时间线，应用日志是诊断信息，两者不能互相替代。

### 15.3 Health

`/health` 至少分别报告 PostgreSQL、Temporal client、Temporal worker、dispatch backlog、signal backlog、secret resolver 和必需内置连接器。总体语义：

- `UP`：可接受并及时调度；
- `DEGRADED`：仍可持久接受，但 Temporal/部分连接不可用或 backlog 超阈值；
- `DOWN`：不能持久接受，或租户/安全基础设施不可用。

### 15.4 运维动作

- DEAD dispatch/signal 可查询、requeue、discard；
- 卡住 Run 检测基于 state heartbeat 和 Temporal describe，不靠任意超时直接改成功/失败；
- 数据库与 Temporal 状态不一致时提供只读 reconcile 报告和显式修复命令；
- 连接健康检查采用指数退避，不能因一个厂商故障拖垮全部 worker；
- 生产部署保留旧 Worker 直到旧 Workflow 不再可达，或使用受支持的 Worker Deployment/versioning 策略。

## 16. SLO、容量与边界

以下是发布目标，不是未测试的现状声明。测量时固定提交、环境和数据集并保留报告。

### 16.1 P0 SLO

| 指标 | 目标 |
|---|---|
| 已返回 202 的触发丢失 | 0 |
| 同一 Trigger Receipt 重复创建 Run | 0 |
| 跨租户读写或 signal | 0 |
| 控制面 API 可用性 | 月度 99.9%，不含计划维护和依赖整体不可用 |
| 控制面读 API p95 | < 500 ms（100 万 Run 元数据量、正常索引） |
| 接受到 Workflow started p95 | < 2 s（50 events/s，Temporal/PG 健康） |
| 取消请求到停止调度新节点 p95 | < 5 s |
| Worker/服务重启恢复排队运行 | < 2 min |
| 非敏感日志/History 中 secret 泄漏 | 0 |

### 16.2 P0 容量验收模型

参考单个 SOAR 部署单元 4 vCPU/8 GiB、独立 PostgreSQL/Temporal：

- 100 个租户、每租户 100 个已发布剧本和 200 条规则；
- 50 个触发事件/秒持续 30 分钟，200/秒突发 60 秒；
- 1,000 个并发 Run，其中 500 个等待审批/Delay；
- 每 Run 平均 20 节点、p95 100 节点；
- 外部 stub 延迟 100 ms、1% 可重试失败、0.1% unknown 场景；
- 重启 API、Worker、PostgreSQL 连接和 Temporal 后验证无丢失、无越权、幂等动作无重复。

P1 目标再提升到 500 events/s 和 10,000 并发 Run。任何数字必须由 `build/benchmark-soar.*` 的可重复报告证明。

## 17. 测试与验收

### 17.1 单元测试

- Graph schema/编译、可达性、环、端口、parallel/join、foreach bound；
- CEL 类型、超时、非法函数、恶意输入和数据路径；
- Playbook/Version/Run/Node/Approval 全状态机；
- risk policy、self-approval、target scope；
- idempotency key 稳定性与 retry/rerun 差异；
- redaction、payload limit、SSRF、redirect/DNS rebinding；
- 每个 Connector 的 schema、错误映射、回执、reconcile 和 compensate。

### 17.2 PostgreSQL 集成测试

- Flyway 从 V1 升至最新；
- 每张表的 tenant 隔离/RLS 负例；
- 并发创建 Trigger Receipt 只产生一个 Run；
- dispatch/signal claim、stale claim recovery、DEAD/requeue；
- 并发审批票、防自批、过期和重复提交；
- 乐观锁保存草稿冲突；
- Run/Node/Event 分页索引计划与 retention。

### 17.3 Temporal 集成与 replay 测试

- 每种节点和分支组合；
- Worker 在 Action 前、远端成功后本地写回前、等待审批时重启；
- Temporal 暂停时 202 后排队，恢复后只启动一次；
- Signal 重复、乱序、过期及 Workflow 已关闭；
- Activity retry、unknown、reconcile、cancel、timer、child workflow；
- 对保存的 History 做 replay，证明新 Worker 兼容旧运行。

### 17.4 API/安全测试

- 每个 endpoint 的正向、401、403、跨租户 404/拒绝、验证错误、409；
- 普通用户不能调用 service-only event 入口；服务身份不能调用用户管理接口；
- 所有 mutation 有审计成功/失败记录；
- 密钥不出现在 API、OpenAPI example、日志、审计、DB snapshot、Temporal History；
- 通用 HTTP 的 metadata IP、私网、redirect、内嵌 credential 和 header 转发攻击；
- 表达式注入、模板注入、超大图、深层 JSON、压缩炸弹、恶意 artifact。

### 17.5 端到端黄金场景

至少固化五条可执行剧本：

1. **高危 IOC 告警**：提取 IOC → TI 查询 → 资产富化 → 建案 → 通知；
2. **恶意终端**：查询终端 → 审批 → 隔离 → 验证 → 更新案件时间线；
3. **凭据泄露**：用户/资产富化 → 人工确认 → 通知身份团队 → 案件任务；
4. **误报路径**：条件不满足 → 给告警加 note/tag → 正常结束，不执行遏制；
5. **连接器不确定结果**：远端超时 → reconcile 失败 → ACTION_UNKNOWN → 人工确认 → 继续。

每条场景验证 UI、API、数据库、Temporal、审计、trace 和业务副作用 receipt，而不是只判断 HTTP 200。

### 17.6 必跑命令

实现完成后至少通过：

```bash
bash build/mvnw.sh -pl services/soar-web -am test -Dsurefire.failIfNoSpecifiedTests=false
bash build/mvnw.sh test -Dsurefire.failIfNoSpecifiedTests=false
cd frontend && corepack pnpm build
cd frontend/apps/workbench && pnpm test
cd frontend/apps/workbench && pnpm verify
python build/verify-soar.py
python build/verify-full.py
python build/failure-tests.py
```

新增 `build/verify-soar.py` 作为 P0 端到端验收入口；若依赖真实厂商，测试环境使用契约一致的 reference adapter，并在报告中明确标注，不能伪装成厂商认证。

## 18. 实施计划

后续实现 agent 应按以下顺序提交小步、可运行的变更，禁止同时重写所有层。

### 阶段 0：契约冻结与测试脚手架

交付：

- 把本设计中的状态、JSON schema、API DTO、错误码形成测试；
- 为旧 API 和 golden demo 建兼容快照；
- PostgreSQL + Temporal + WireMock 集成测试 profile；
- secret leak 扫描和 Temporal History replay 测试工具。

退出条件：旧测试全绿，新 P0 测试可先红但均有明确归属。

### 阶段 1：版本化控制面

交付：

- V6 migration，Playbook/Version aggregate、Repository、CRUD、乐观锁；
- Definition schema、compiler、validator、CEL 封装；
- Action Catalog 的只读 descriptor，不执行动作；
- 发布不可变和风险摘要；
- 旧 Playbook 导入器。

退出条件：可以通过 API 创建图定义、校验、发布、克隆版本；无执行能力也不能绕过发布规则。

### 阶段 2：持久异步运行内核

交付：

- V7/V8 migration；receipt、run、node、attempt、event、dispatch outbox；
- `202` 手工运行和事件入口；
- Generic Temporal Workflow；START/END/ACTION/CONDITION/SWITCH/SET_VARIABLE；
- 运行列表/详情/事件、取消、指标和 health；
- 移除生产进程内副作用回退。

退出条件：重启/重复投递/Temporal 短时故障下不丢已接受运行，节点状态可解释。

### 阶段 3：连接器与安全动作

交付：

- V9 migration、Connector SDK、Connection/SecretResolver、test connection；
- SOCP Alert/Incident/Search/Asset/Threat/Notify 内置动作；
- 受限 HTTP connector；
- endpoint/firewall reference adapter contract；
- idempotency、receipt、reconcile、unknown、redaction 和 SSRF 测试。

退出条件：黄金场景 1 可真实闭环；高风险动作未审批绝不调用远端。

### 阶段 4：完整控制流和人工协作

交付：

- PARALLEL/JOIN/FOREACH/DELAY/SUB_PLAYBOOK；
- V10 migration、Approval/Decision/Manual Task/Signal Outbox；
- 风险策略、拒绝/过期分支、self-approval 防护；
- retry/rerun/resolve unknown。

退出条件：黄金场景 2~5、Signal 并发与 Worker replay 测试通过。

### 阶段 5：自动化规则与上下游事件

交付：

- Automation Rule CRUD/test/enable；
- Alert 全量 envelope 转换；Incident transactional outbox；
- typed event evaluator、priority、cooldown、groupBy、capacity、loop prevention；
- 旧 `/playbooks/evaluate` 适配到新入口。

退出条件：同一 at-least-once 事件只产生期望的 Run，抑制有 receipt，可解释命中原因。

### 阶段 6：Workbench

交付：

- Playbooks、Rules、Runs、Approvals/Tasks、Connections 五个页面；
- Vue Flow 编辑器、schema 表单、数据选择器、校验、dry-run、发布；
- Run 图/时间线/SSE；
- i18n、权限、可访问性、单测和 Playwright 场景。

退出条件：分析员不借助 curl 即可完成五条黄金场景；视觉变更附截图或人工验证说明。

### 阶段 7：迁移、容量和发布

交付：

- V11、retention/artifact；
- legacy 双读/导入报告和逐租户切换开关；
- chaos、capacity、security 报告；
- OpenAPI、运行手册、告警规则和 dashboard；
- prod profile fail-fast 与 Worker 安全部署说明。

退出条件：本设计 P0 DoD 全部通过，旧执行器无流量后才允许删除。

## 19. 兼容与迁移

### 19.1 旧数据处理

- V1~V5 表不修改、不删除；新表 side-by-side；
- 每个旧 `t_playbook` 转换为一个 `LEGACY_IMPORTED` 草稿，线性 action 映射为 ACTION 节点；
- 能明确识别且配置完整的 action 生成合法 `actionRef`；未知、模拟或裸 URL 动作产生 validation error；
- 导入默认不发布、不创建 Automation Rule，管理员逐个校验；
- 旧 enabled playbook 在租户切换前仍由 legacy route 执行，切换事务中禁用旧 route 并启用新规则，不能双跑；
- 保存迁移报告：oldId、newId/version、映射、错误、操作者和时间。

### 19.2 API 兼容

- 旧 list/get 在兼容期返回旧 shape；新 UI 使用新 DTO；
- 旧 create/toggle/delete 标记 deprecated，并可通过配置关闭 mutation；
- 旧 manual execute 在 legacy tenant 上保持原同步契约；新调用者必须迁到 `POST /api/v2/runs`，不得在同一路径下把同步响应偷偷改成异步响应；
- Alert Web 的 `SoarClient` 已升级为调用 `/api/v2/events/evaluate`；旧 `/api/v1/playbooks/evaluate` 在迁移窗口仅做同一 envelope/application service 的兼容适配，不得和 V2 evaluator 重复消费同一事件；
- 租户切换到 V2 后，旧 mutation/execute 返回带迁移链接的 `410 Gone`；旧 evaluate 仅在 V2 evaluation 开启时进入同一 V2 evaluator，关闭迁移窗口后再返回 `410`；旧 list/get/executions 可继续只读；
- 旧 executions 在只读兼容期可从新 Run projection 映射最近 200 条；
- 至少保留一个小版本和一个明确发布日期窗口后再移除 V1；移除时间写入 OpenAPI deprecation 和 migration note。

### 19.3 Feature Flag

建议：

```text
socp.soar.v2-control-plane-enabled
socp.soar.v2-execution-enabled
socp.soar.v2-evaluation-enabled
socp.soar.execution-tenant-allowlist
socp.soar.legacy-mutation-enabled
socp.soar.legacy-execution-enabled
```

部署环境可用同名环境变量覆盖：

```text
SOCP_SOAR_V2_CONTROL_PLANE_ENABLED
SOCP_SOAR_V2_EXECUTION_ENABLED
SOCP_SOAR_V2_EVALUATION_ENABLED
SOCP_SOAR_V2_TENANT_ALLOWLIST
SOCP_SOAR_LEGACY_MUTATION_ENABLED
SOCP_SOAR_LEGACY_EXECUTION_ENABLED
```

`execution-tenant-allowlist` 为空表示所有租户；非空时只允许逗号分隔的租户 ID
进入 V2 durable execution，控制面读接口仍可用于迁移核对。`prod` 通过
`ProdGuard` 拒绝全局同时开启 V2 execution 与 legacy execution；租户灰度必须先
关闭该租户旧入口，再把租户加入 allow-list，避免同一事件双跑。

## 20. P0 Definition of Done

以下全部满足才能称为“SOAR 2.0 达到主流核心基线”：

- [ ] 剧本是版本化图定义，不再以字符串列表作为主模型；
- [ ] 发布版本不可变，草稿有乐观锁、校验、风险摘要和审计；
- [ ] Automation Rule 与 Playbook 解耦，typed event/CEL 匹配可解释；
- [ ] P0 13 类节点都有单元、Temporal 集成和 UI 支持；
- [ ] 执行 API 异步返回 202，所有已接受运行可恢复；
- [ ] Run/Node/Attempt/Event 可分页查询并在 UI 排障；
- [ ] 连接器有 schema、Connection、secret ref、健康和回执；
- [ ] 至少六个 SOCP 内置连接器形成真实调查/案件/通知闭环；
- [ ] 高风险动作逐节点审批、防自批、拒绝/过期可控；
- [ ] unknown、reconcile、compensate、cancel、retry/rerun 语义清晰；
- [ ] prod 不执行 simulation 或进程内 fallback；
- [ ] tenant、RBAC、SSRF、表达式、secret redaction 负例全绿；
- [ ] 告警和案件事件 at-least-once 重放不会重复创建 Run；
- [ ] 五条黄金场景通过且保留业务回执、审计和 trace 证据；
- [ ] Maven、前端、PostgreSQL、Temporal replay、chaos 和容量门禁通过；
- [ ] 文档、OpenAPI、运维手册、指标 dashboard 和告警规则齐备；
- [ ] 真实厂商动作只有经过目标环境认证后才标记 `PRODUCTION_READY`。

## 21. 实现约束清单

这是给后续 agent 的硬约束：

1. 不修改 Flyway V1~V5，不物理删除旧数据。
2. 不把 JPA Entity、Temporal DTO 或 connector 原始响应直接暴露为 API DTO。
3. 不在 Controller 里写工作流、权限策略或状态转换。
4. 不在 Workflow 内访问 Spring、数据库、HTTP、随机数或系统时间。
5. 不以 `Map<String,Object>` 作为新的核心领域接口；仅在 JSON/connector 边界使用受 schema 约束的树结构。
6. 不用 action 文本包含关系决定动作、风险或审批。
7. 不自动重试 `idempotency=NONE` 的未知副作用。
8. 不让 secret 值进入 Temporal 参数/结果。
9. 不用“返回 HTTP 200”或“UI 显示绿色”代替业务回执验证。
10. 不把 H2、mock、simulation 或 reference adapter 结果写成生产认证。
11. 不为追求兼容维护两套长期执行语义；legacy 只能是有截止期的适配层。
12. 每一阶段先补失败测试，再实现；提交保持 Conventional Commit 风格和单一职责。

## 22. 参考资料

- [OASIS CACAO Security Playbooks v2.0](https://docs.oasis-open.org/cacao/security-playbooks/v2.0/security-playbooks-v2.0.html)：标准化 playbook、workflow、step、command、agent/target、版本和签名概念。
- [Microsoft Sentinel：Create and manage playbooks](https://learn.microsoft.com/en-us/azure/sentinel/automation/create-playbooks)：incident/alert/entity 触发、自动/手工运行和运行历史。
- [Microsoft Sentinel：Automation rules](https://learn.microsoft.com/en-us/azure/sentinel/automate-incident-handling-with-automation-rules)：触发、条件、顺序动作、案件任务和服务身份。
- [Splunk SOAR：Visual Playbook Editor blocks](https://help.splunk.com/en/splunk-soar/soar-cloud/build-playbooks/use-the-playbook-editor-to-create-and-view-playbooks-to-automate-analyst-workflows/add-a-new-block-to-your-splunk-soar-cloud-playbook)：Action、Playbook、Code、Utility、Filter、Decision、Prompt 和审批/延迟等编辑能力。
- [Splunk SOAR：Apps and assets](https://help.splunk.com/en/splunk-soar/soar-cloud/administer-soar-cloud/manage-your-splunk-soar-cloud-apps-and-assets/add-and-configure-apps-and-assets-to-provide-actions-in-splunk-soar-cloud)：连接器能力与多个配置实例的分离。
- [Cortex XSOAR：What is a playbook](https://docs-cortex.paloaltonetworks.com/r/Cortex-XSOAR/8/Cortex-XSOAR-Cloud-Documentation/What-is-a-playbook?contentId=Ywl4vBEHQvXbsTK8UILjMg)：任务类型、第三方命令、内置脚本和人工输入的编排模型。
- [Elastic Security：Third-party response actions](https://www.elastic.co/guide/en/security/current/response-actions-config.html)：EDR 第三方响应、最小权限和读写凭据分离。
- [Common Expression Language](https://cel.dev/overview/cel-overview)：可移植、安全、可类型检查的表达式设计。
- [Temporal Documentation](https://docs.temporal.io/)：持久 Workflow、Activity、消息传递、重试和生产部署。
- 仓库现有约束：[architecture.md](architecture.md)、[idempotency-contract.md](idempotency-contract.md)、[api-contract.md](api-contract.md)、[maturity-matrix.md](maturity-matrix.md)、[soar-action-connectors.md](soar-action-connectors.md)。

## 23. 当前落地切片（2026-09）

本轮已在 `services/soar-web` 落地可运行的 V2 控制面与执行骨架，作为后续生产接入的边界：

- `V6~V22`：版本、运行/outbox、节点/事件、审批/逐审批人决策、自动化规则、连接器、attempt、人工任务、按 gate/node 隔离的 signal outbox、artifact、审批角色/组策略、远端操作时间、连接版本、保留索引、row version 默认值、共享执行预算和租户级外键的增量迁移；
- `SoarDefinitionValidator` + safe CEL 子集：schema、节点/边、可达性、规模/并发/重试上限、结构化控制流、风险摘要和 action-ref 校验；
- `/api/v2`：playbook/version 草稿发布、异步运行（`202`）、分页运行/事件/attempt、SSE、取消、retry/rerun/unknown resolve、审批/人工任务、automation rule、连接/动作目录、health/stats 和死信运维；
- `SoarV2DispatchWorker` + Temporal V2 workflow/activity：稳定 workflow ID、execution series、节点幂等、attempt receipt、bounded retry、outbox/signal 重投；Temporal 不可用时保持 `QUEUED`，不在 V2 隐式回退进程内；
- 第 19.3 节 rollout flags 已接入运行路径：V2 control-plane/execution、租户 allow-list 和 legacy execution/mutation 开关会在控制面、事件入口、旧路由及 dispatch/signal worker 生效；暂停 V2 时只保留 durable backlog，不会转入进程内执行；
- `SoarV2RunRecoveryWorker` 只处理有界陈旧投影：普通 `DISPATCHING/RUNNING` 投影先 describe Temporal，工作流仍开放或状态未知时保持原状；仅确认工作流已关闭才转 `ACTION_UNKNOWN`，无工作流的孤立投影才转 `TIMED_OUT`；已提交的 `CANCELLING` 不再被恢复器改写成超时/未知，而是单向收敛为 `CANCELLED`，避免恢复任务制造重复副作用；
- 终态单向栅栏覆盖 Activity action/compensate、节点/人工/审批 timer、控制面审批/人工/unknown、signal/dispatcher/requeue 和 Temporal completion；`PARTIALLY_SUCCEEDED`、取消中与已终态请求均拒绝晚到恢复，唯一可恢复例外是显式 requeue 的 `DEAD` dispatch，迟到 signal 会持久化为 `CANCELLED`；
- V2 人审门：高风险版本在 dispatch 前进入 `WAITING_APPROVAL`，显式 `APPROVAL/MANUAL_TASK` 节点通过 durable signal 等待批准/拒绝/结构化输入；每个审批闸门保存具体动作、输入哈希和脱敏目标快照，并按审批人持久化投票；
- Connector SDK/SecretResolver：六个 SOCP 内置动作域、受限 HTTP/endpoint/firewall adapter、secret 仅在 Activity 解析、SSRF allowlist 和认证头注入；
- 五个版本化黄金场景 JSON 模板通过 `/api/v2/templates` 提供，安装只创建租户草稿；Workbench 保持 V1 兼容，并提供覆盖 13 类节点的轻量图编辑器、版本校验/dry-run/发布、durable run inspector、attempt/event/artifact 排障和 SSE 实时投影；告警处置备注使用 V18 durable idempotency key。

本轮已补齐的关键生产语义包括：Alert→V2 durable evaluation 单路径、发布期子剧本已发布/无环/最大深度 5 门禁、父子/并行共享 Run 级执行预算、数据库行锁的多实例 rule admission（含 revision 幂等键）、安全正则上限、真实会话 Cookie/OpenAPI `ApiResult`/`If-Match` 契约、V21/V22 数据库完整性约束，以及 Temporal 不可用时只保留 durable backlog 的故障脚本。仍需在目标环境完成的发布门禁包括：真实厂商 endpoint/firewall/EDR 认证与 reconcile/compensate、Vault/KMS/Kubernetes Secret provider 与轮换、对象存储 artifact、PostgreSQL/Temporal replay/chaos/capacity、安全负例、图编辑器的完整 Playwright/可访问性验收和五条场景的 UI/业务副作用证据。当前实现提供受控 adapter 和明确的 `PRODUCTION_READY` 前置条件，不把模拟调用标记为生产成功；发布仍必须以第 20 节 DoD 为准。
