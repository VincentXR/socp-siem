# SOCP 安全运营平台（SOCP SIEM）

企业级安全运营中心（Security Operations Center）单仓实现：**17 个 Java 21 微服务 + 10 个横切平台模块 + 统一前端控制台**，生产链路真实接线（Kafka / OpenSearch / ClickHouse / PostgreSQL / Grafana）。

## 项目亮点

- **17 个微服务真实实现**（非骨架）：告警 / 日志检索 / 检测引擎 / SOAR 剧本 / 报表 / 资产管理 / HIPS / 威胁情报 / 案件工单等，全部可运行、可验证
- **生产链路真实接线**：事件采集 → Kafka 事件流 → 检测引擎 → PG 告警落库 → ClickHouse 报表聚合 → Grafana 监控，全链路 E2E 验证
- **强制 JWT 验签**：HMAC HS256 全服务验签（无 token / 伪造 token / 过期 token 一律 401），服务间调用自动换取令牌
- **多租户隔离**：SDK 级强制（TenantContext + BaseEntity.tenantId），租户数据物理隔离
- **62 项自动化 E2E 验证**：覆盖健康检查、全链路、持久化、鉴权、多租户、限流、链路追踪

## 架构总览

```
接入层        Vector/Falco（Agent 配置就绪） ──► search-config（事件归一化）
                                          │
业务服务层    17 个 Spring Boot 3.5 服务（Java 21）
             ┌─────────────────────────────────────────────────┐
             │ alert-web · search-config · detect-web · soar-web │
             │ report-web · asset-web · hips-web · threat-web    │
             │ incident-web · notify-web · attack-web · ...      │
             └─────────────────────────────────────────────────┘
中间件层      PostgreSQL（告警/案件/情报） · Kafka（事件流） · OpenSearch（检索）
             · ClickHouse（报表聚合） · Redis · MinIO · Prometheus + Grafana
```

**主链路（已接线并验证）**：
`search-config 采集 → Kafka socp-events → detect-web 规则引擎 → alert-web 告警落库(PG) + 写 ClickHouse → report-web 报表聚合 → Grafana 监控`

## 中间件接线状态（真实）

| 中间件 | 状态 | 用途 |
|---|---|---|
| **PostgreSQL** | ✅ 已接线 | 告警 `t_alarm` / 案件 / 情报，Flyway V1 迁移 |
| **Kafka** | ✅ 已接线 | `socp-events` 事件流（search→detect），`socp-audit` 审计出口 |
| **OpenSearch** | ✅ 已接线 | `socp-events-yyyy.MM.dd` 按天索引，HTTPS + basic auth |
| **ClickHouse** | ✅ 已接线 | `alert_agg.alarm_detail` 告警明细，REPORT 报表聚合优先查 CK |
| **Grafana + Prometheus** | ✅ 已接线 | 17 服务指标抓取全 UP，「SOCP 运维大盘」 |
| **Redis** | 🟡 编排就绪 | 限流用进程内令牌桶（可切换 Redis） |
| **MinIO** | 🟡 编排就绪 | 对象存储（资产附件）预留 |
| **Temporal** | 🟡 编排就绪 | SOAR 剧本当前为进程内执行器（可切 Temporal Saga） |
| **Keycloak** | 🟡 验签侧就绪 | 配 `socp.security.issuer-uri` 即可校验其签发的 JWT（当前用 HMAC 对称密钥） |

## 服务清单（17 个）

| 服务 | 端口 | 安全域 | 状态 |
|---|---|---|---|
| soc-base | 18086 | SOC | ✅ 租户管理 + 平台概览 |
| asset-web | 18085 | ASSET | ✅ 资产管理 CRUD + 采集上报 + 统计 |
| asset-collect | 18091 | ASSET | ✅ 定时资产扫描模拟器 → 上报 asset-web |
| alert-web | 18080 | ALERT | ✅ 告警 CRUD + 富化 + ClickHouse 上报（PG 落库） |
| search-config | 18081 | SEARCH | ✅ 事件归一化 + Kafka 生产 + OpenSearch 写入 + 批量转发 |
| detect-web | 18082 | DETECT | ✅ 规则引擎 23 条 + Kafka 消费 + 背压 503 + SSE 推送 |
| detect-model | 18090 | DETECT | ✅ 5 分钟滑动窗口聚合（按规则/实体/级别 + 分钟级趋势） |
| hips-web | 18087 | HIPS | ✅ 端点注册/心跳/事件接收 + 统计 |
| hips-collect | 18093 | HIPS | ✅ Falco 事件定时模拟器 → 上报 hips-web |
| soar-web | 18083 | SOAR | ✅ 剧本 CRUD + 触发编排 + 手动执行 + 动作派发（通知/建案/webhook） |
| report-web | 18084 | REPORT | ✅ 日报 + 7 日趋势（ClickHouse 聚合优先，失败回退） |
| ai-assistant | 18088 | AI | ✅ 关键词知识库问答（未接外部 LLM） |
| api-gateway | 18092 | 网关 | ✅ Spring Cloud Gateway 路由 + JWT 验签 + RBAC(viewer 只读) + traceId |
| threat-web | 18094 | THREAT | ✅ 威胁情报 IOC 管理 + 命中富化（H2 落库） |
| attack-web | 18095 | ATT&CK | ✅ 战术/技术矩阵 + 检测覆盖率 |
| notify-web | 18096 | 通知 | ✅ 通知渠道（内置/Webhook）+ 告警派发 |
| incident-web | 18097 | 案件 | ✅ 案件归并 + 时间线 + 告警自动建案 |

platform 横切模块 **10 个**：`socp-auth`（JWT 验签，强制模式）· `socp-tenant` · `socp-audit` · `socp-ratelimit` · `socp-error` · `socp-rule`（规则引擎）· `socp-data` · `socp-obs` · `socp-bom` · `socp-test`

前端 **1 个 app**：`apps/workbench`（dev 5173）——统一控制台，Stripe 风格亮色主题，真实对接 17 个后端 API。

## 快速开始

前置：Docker Desktop（虚拟化已开启）、JDK 21（本仓 `build/mvnw.sh` 内置）、Node.js 22+。

```bash
# 1) 起 8 个中间件（PG / Kafka / OpenSearch / ClickHouse / Redis / MinIO / Prometheus / Grafana）
docker compose -f socp/infra/docker-compose.yml up -d

# 2) 构建（27 模块，内置阿里云镜像 + 本仓 JDK21）
bash socp/build/mvnw.sh -DskipTests package

# 3) 起 17 个后端服务（端口 18080~18097，日志 .cache/*.log）
bash socp/build/run-all.sh backend

# 4) 前端（dev server）
cd socp/frontend/apps/workbench
node ../../node_modules/vite/bin/vite.js --port 5188
```

访问：
- **SIEM 控制台**：http://localhost:5188 （登录 demo / demo123）
- **Grafana 监控**：http://localhost:3000 （admin / Socp@2026）
- 中间件：PG `socp/socp` · OpenSearch `admin/Socp!Sec2026xK` · ClickHouse `default/socp`

> ⚠️ 凭据为演示用途，生产请用环境变量（`SOCP_JWT_SECRET` / `SOCP_DEV_BYPASS` / `SOCP_PG_*` 等）覆盖。

## 自动化验证

```bash
# 62 项全栈 E2E：健康检查 + 采集→检测→告警→富化→通知→建案→SOAR + 持久化 + 情报
python socp/build/verify-full.py

# 18 项纵切验证（经网关）：鉴权 / 多租户隔离 / 审计 / 限流 / 链路追踪
python socp/build/verify-slice.py
```

实测：**verify-full 62/0、verify-slice 18/0 全绿**。

## 已知边界（诚实声明）

- **RBAC**：已实现角色授权——`@RequireRole` 注解（admin/analyst/viewer）+ 网关全局 viewer 只读兜底；未做角色/权限的运行时管理 UI（角色由 JWT claim 决定）
- **审计日志**：`@AuditOperation` 注解 + 内存 sink + 查询 API（`GET /soc-base/api/v1/audit/records|stats`）；生产可切 Kafka sink
- **ai-assistant**：关键词问答库，未接外部 LLM API
- **SOAR**：剧本执行器为进程内实现，未用 Temporal Saga（无补偿/重试编排）
- **Temporal / Keycloak realm / MinIO 业务**：编排就绪但未跑业务链路（验签侧已支持 Keycloak 签发 JWT）
- **测试**：20 个单元/切片测试类，无跨服务集成测试（由 verify-full.py 覆盖）

## 目录结构

```
socp/
├── pom.xml                 # 根 BOM，27 模块
├── platform/               # 10 个横切模块（auth/tenant/audit/ratelimit/obs/error/data/rule/bom/test）
├── services/               # 17 个业务服务
├── frontend/               # pnpm monorepo（apps/workbench 唯一 app）
├── agents/                 # 端点 Agent 配置（Falco 规则 + Vector）
├── infra/                  # docker-compose + init-sql（PG/Kafka/CK/Keycloak/Prometheus）
├── build/                  # 构建脚本 + 62 项 E2E 验证
└── docs/                   # 架构/模块/迁移文档
```

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway 2025.0 · Maven 多模块 · nimbus-jose-jwt（JWT）· PostgreSQL 18 · Apache Kafka · OpenSearch · ClickHouse · Prometheus + Grafana · Vue 3 + Vite + Element Plus + ECharts
