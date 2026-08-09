# 快速开始

## 1. 前置
- 本仓 JDK：`tooling/jdk-21.0.12+8`
- Maven 包装：`bash socp/build/mvnw.sh`（内置阿里云镜像，绕开损坏的 mvn 脚本）
- Node/pnpm：`tooling` 外的 managed node 22 + corepack pnpm 10（前端构建用）
- Docker（中间件用，当前可选——全栈内存态可无 docker 运行）

## 2. 后端构建
```bash
# 全量构建 27 模块（10 platform + 17 services，跳过测试）
bash socp/build/mvnw.sh -DskipTests package
```
> 全仓 20 个测试类 / 8 个模块（socp-rule 3、search-config 3、asset-web 3、soc-base 3、hips-web 2、hips-collect 2、asset-collect 2、soar-web 2），
> 均为单元/切片测试、零跨服务集成测试；为缩短构建时间默认 `-DskipTests`，需要跑测试去掉该参数即可。

## 3. 前端构建（首次需装依赖）
```bash
cd socp/frontend
# 用 corepack 跑 pnpm（npx pnpm 会被沙箱拦）
corepack pnpm@10.0.0 install --store-dir ./.pnpm-store
# 构建前端（monorepo 下当前只有 workbench 一个 app）
corepack pnpm@10.0.0 -r build
```
> `apps/` 下只有 `workbench` 一个应用；早期文档提到的 alert/search/soar/report 四个 app 已于 2026-08-08 清理，功能折叠进 workbench。

## 4. 一键启停（全栈 17 后端 + 1 前端）
```bash
# 启动全部（后端 + 前端 dev server）
bash socp/build/run-all.sh start

# 只起后端
bash socp/build/run-all.sh backend

# 只起前端
bash socp/build/run-all.sh frontend

# 查看状态
bash socp/build/run-all.sh status

# 停止全部
bash socp/build/run-all.sh stop
```

## 5. 端口与服务对照表

### 后端 17 个服务（18080~18097）
| 端口 | 服务 | context-path | 功能 | 存储 |
|------|------|-------------|------|------|
| 18080 | alert-web | /alert-web | 告警 CRUD 落库 + 鉴权 + 限流 | H2 文件库 |
| 18081 | search-config | /search-config | 日志源配置 + Vector 渲染 + ingest | H2 文件库 |
| 18082 | detect-web | /detect-web | 规则 CRUD + 引擎 + 背压 503 | 内存 |
| 18083 | soar-web | /soar-web | 剧本编排 CRUD（非 Temporal） | 内存 |
| 18084 | report-web | /report-web | 报表日报 + 趋势（非 ClickHouse） | 内存 |
| 18085 | asset-web | /asset-web | 资产管理 CRUD | 内存 |
| 18086 | soc-base | /soc-base | 租户管理 + 平台概览 | 内存 |
| 18087 | hips-web | /hips-web | 端点注册/心跳 | 内存 |
| 18088 | ai-assistant | /ai-assistant | 自然语言安全问答（关键词库） | 内存 |
| 18090 | detect-model | /detect-model | 二次关联分析（🟡 复用 socp-rule 引擎，未接 Kafka） | 内存 |
| 18091 | asset-collect | /asset-collect | 资产采集入口 | 内存 |
| 18092 | api-gateway | （根路径） | 统一网关鉴权/traceId | — |
| 18093 | hips-collect | /hips-collect | Falco 事件采集入口 | 内存 |
| 18094 | threat-web | /threat-web | 威胁情报（IOC）管理 | H2 文件库 |
| 18095 | attack-web | /attack-web | ATT&CK 战术/技术矩阵 | 内存 |
| 18096 | notify-web | /notify-web | 通知渠道配置与下发 | 内存 |
| 18097 | incident-web | /incident-web | 安全案件/工单 | H2 文件库 |

> ⚠️ **鉴权提示**：**17 个服务全部**接入了 `socp-auth`，所有 API 都要求 `Authorization: Bearer <token>`（缺失返回 401）。
> 但仓库内**未配置任何密钥源**（`socp.security.issuer-uri` / `jwt-secret`），`JwtValidator` 因此运行在
> **dev-bypass** 模式：**任意非空 token 均放行**，不验签、不查过期（启动日志会打 WARN）。
> 另外**授权 RBAC 尚未实现**，通过认证即可访问全部接口。**仅适用于本地开发。**
>
> 要打开真实校验：配 `socp.security.issuer-uri=<Keycloak realm 地址>`（或 `jwt-secret`，≥32 字节）
> 并设 `socp.security.dev-bypass=false`。

### 前端 dev server（统一 Shell，单端口）
| 端口 | App | 说明 |
|------|-----|------|
| 5173 | workbench | **唯一前端应用**。统一控制台 Shell（暗色侧边栏切 11 模块：概览/告警/检索/接入/元数据/规则/编排/报表/资产/端点/AI）。早期的 alert/search/soar/report 四个独立 app 已删除，功能折叠进此 Shell |

## 6. 快速验证
```bash
# 后端 API 冒烟
curl localhost:18083/soar-web/api/v1/playbooks        # SOAR 剧本
curl localhost:18084/report-web/api/v1/reports/daily      # REPORT 日报
curl localhost:18085/asset-web/api/v1/assets             # ASSET 资产
curl localhost:18086/soc-base/api/v1/overview          # SOC 概览
curl localhost:18087/hips-web/api/v1/endpoints         # HIPS 端点
curl -X POST localhost:18088/ai-assistant/api/v1/ai/ask -H "Content-Type: application/json" -d '{"question":"暴力破解"}'  # AI 问答
curl localhost:18090/detect-model/api/v1/stats            # DETECT 聚合统计

# alert-web 需鉴权
curl -H "Authorization: Bearer demo-token" localhost:18080/alert-web/api/alarms

# 新增 4 个服务
curl localhost:18094/threat-web/api/v1/iocs                # 威胁情报
curl localhost:18095/attack-web/api/v1/techniques      # ATT&CK 技术
curl localhost:18096/notify-web/api/v1/channels        # 通知渠道
curl localhost:18097/incident-web/api/v1/incidents             # 安全案件

# 前端打开浏览器
# http://localhost:5173  统一控制台（侧边栏切模块）
```

## 7. 起中间件（可选，docker 就位后）

> ⚠️ **中间件基本未在 Java 代码中接线**：OpenSearch / ClickHouse / Redis / Temporal / PG+AGE / MinIO
> **零客户端引用**；Kafka 仅有审计出口（需显式配 `socp.audit.sink=kafka`）；Keycloak 仅验签侧可用
> （需配 `socp.security.issuer-uri`）。默认配置下起这些容器**不会改变服务行为**，服务仍以内存态 / H2 运行。
> 此步骤主要用于提前验证编排，需要本机具备 Docker 运行时。

```bash
cd socp/infra && docker compose up -d
# 含 PG18(+AGE)/OpenSearch/Kafka/Redis/ClickHouse/Temporal/Keycloak/MinIO/Prom/Grafana/Loki/Jaeger
```
init-sql 已就绪：pg/age/clickhouse/kafka/keycloak/prometheus。

注意：`micrometer-registry-prometheus` 已是 17 个服务的依赖，但 `/actuator/prometheus`
**目前只有 api-gateway（18092）暴露**；其余 16 个服务的 `management.endpoints.web.exposure.include`
仅含 `health,info`（detect-web / search-config / alert-web 另含 `metrics`），抓取会返回 404 —— 属预期现象，
把 `prometheus` 加进各服务的 include 列表即可开启（无需改 prometheus.yml）。

## 8. 端点 Agent（采集）
```bash
# Vector 转发到 SEARCH：
tooling/vector/bin/vector.exe --config socp/agents/vector-pipeline/vector.toml
```
Falco 规则：`socp/agents/falco-rules/falco_rules.yaml`。

## 9. 端口约定
- **避开 8080**（旧 SIEM 控制台占用，任意路径返 HTML，致健康检查假阳性）。
  因此 docker-compose 中的 Keycloak **宿主端口已从 8080 改为 8083**（容器内仍为 8080），
  访问地址为 `http://localhost:8083`。Temporal UI 为 8088，同样避开 8080。
- 环境存在 `SERVER__PORT=0` 环境变量，Spring 会折叠成 `server.port=0`，故启动一律用 `--server.port=` 命令行参数覆盖。
