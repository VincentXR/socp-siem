# 迁移设计：com.siem → SOCP（已完成，com.siem 已删除）

## 决策与状态
- **决策（2026-08-07）**：用户确认"迁 com.siem 进 socp，都做"，并明确要求"完成迁移并删除 com.siem"。
- **当前状态**：**SOCP 为唯一真源（Source of Truth）**；com.siem 源码与产物已由用户手动删除，`src/ build/ test/ config/ demo/ lib/ frontend/ build.sh run_tests.sh vector.toml FROZEN.md` 均已移除。
- 理由：com.siem 是单进程零依赖引擎，其解析 / 规则 / 事件模型经实战检验，作为 socp 起点；现 socp 已完整接管且为功能超集（元数据 / SPL / 编排 / 报表 / 资产 / 端点 / AI / 网关 / 多租户），继续维护双份无意义。
- 保留项：仅 `tooling/`（JDK 21 / Maven / Vector 二进制）为 SOCP 构建运行共用，不删。

## 能力迁移清单（全部 ✅）
| 能力 | com.siem 位置 | 迁入 socp 目标 | 状态 |
|---|---|---|---|
| 解析链 | `parse/ParserChain` + 5 解析器 | `search-config`：ParseRule + 解析预览 | ✅ |
| 事件模型 | `model/SecurityEvent` | `platform/socp-rule` model | ✅ |
| 规则引擎 | `engine/RuleEngine` + `config/Rules` | `platform/socp-rule`（忠实移植） | ✅ 单测 9/9 |
| Vector 契约 | `QueryServer` /api/ingest/bulk | `search-config` /api/v1/ingest | ✅ |
| 背压 503 | `RuleEngine.ingest` boolean + queueLoad | SEARCH / DETECT ingest 同语义 | ✅ |
| 信封解析 | `ingest/VectorEnvelope` | `search-config` ingest 复用 | ✅ |
| 告警存储 | `AlertStore` | `alert-web` t_alarm + 工单处置 | ✅ |
| 规则可视化 / 编排 | （无） | `detect-web` 规则 CRUD + workbench 编排 UI | ✅ |
| SPL 检索 | （无） | `search-config` SplEngine | ✅ |
| 元数据管理 | （无） | `search-config` MetaController | ✅ |

## 架构边界（socp 主链路）
- 采集 / 传输：Vector / Falco（`socp/agents/`）—— 与 com.siem 时期契约不变（NDJSON 信封 / healthcheck 关闭 / disk buffer / retry 5）。
- 解析 / 检索：SEARCH → OpenSearch（替代内存 ParserChain + 查询）。
- 检测 / 分析：DETECT（socp-rule）+ 窗口聚合 → Kafka → PG + AGE 关联图。
- 响应：SOAR via Temporal。
- 报表：REPORT via ClickHouse。
- 去重 / 抑制：沿用 com.siem Suppressor 思路，迁为 DETECT 侧组件（socp-rule Suppressor）。

## 迁移原则（回顾）
1. **单一可信解析路径**：解析只在 SEARCH 做，Vector 只采集传输。
2. **契约优先**：NDJSON 信封、healthcheck 关闭、disk buffer、retry 5 等坑带入 `agents/` 配置。
3. **集群无关先行**：先落不依赖中间件的部分，再按链路接 OpenSearch / Kafka / PG。
4. **逐链路验证**：每填一个服务即接进 run-slice 做端到端。

## 执行顺序（全部完成 ✅）
1. ✅ agents/ + docs/ + SEARCH 起手（配置 + 渲染 + ingest 接收）
2. ✅ DETECT（规则引擎，迁 RuleEngine / Rules → socp-rule）
3. ✅ SOAR（Temporal Saga）
4. ✅ REPORT（ClickHouse）
5. ✅ asset / soc / hips / ai 补全
6. ✅ 前端 5 app 真实实现（收敛为 workbench 统一 Shell）

## 退役计划（com.siem → 已删除）
- **已删除（2026-08-07）**：用户手动删除 `src/ build/ test/ config/ demo/ lib/ frontend/ build.sh run_tests.sh vector.toml FROZEN.md`，SOCP 成为唯一代码真源。
- **共用基座保留**：`tooling/`（JDK 21 / Maven / Vector 二进制）与 `socp/agents/vector-pipeline/` 仍共用，不随 com.siem 删除而移除。
- **后续**：新需求一律落在 `socp/`，不再回头维护 com.siem。
