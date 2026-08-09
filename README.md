# SOCP 安全运营平台（单仓多模块）

依据《SOCP 架构分析报告》定稿方案落地。本仓为 **Maven 多模块（后端 17 服务）+ pnpm monorepo（前端 1 app）** 单仓，中间件编排见 docker-compose（当前未接线，详见「当前限制」）。

> 当前进度：已落地**可运行纵切切片** + **全栈基础设施配置**。按 P0–P18 顺序逐域填充。
> **重要**：docker-compose 声明的中间件中，除 Kafka（审计出口，opt-in）外**均未在 Java 代码中接线**，服务默认以内存态/H2 运行。请先读「当前限制 / 尚未接线」再评估落地度。

## 架构总览（速读）
- **三横层**：接入/端点层（Keycloak/Falco/Vector — 均为规划）· 业务服务层（**17 个** Java 21 服务，已实现）· 中间件层（PG+AGE/OpenSearch/Redis/ClickHouse/Temporal/MinIO — **仅编排，未接线**；Kafka 仅审计出口、Keycloak 仅验签侧）。
- **主链路（目标架构，尚未接线）**：Vector/Falco → SEARCH 管道 → OpenSearch（检索）；DETECT 规则引擎 → Kafka 窗口聚合 → PG `t_alarm` + AGE 关联图；SOAR（Temporal Saga）响应；REPORT（ClickHouse）报表；ALERT 看板下钻。
  > 当前实际：SEARCH/DETECT/SOAR/REPORT 均在**进程内内存态**完成对应逻辑，未经过 OpenSearch/Kafka/Temporal/ClickHouse。
- **横切现状**：鉴权已实现**真实 JWT 校验**（nimbus-jose-jwt：JWKS 非对称 / HMAC 对称验签 + `exp`/`nbf` + issuer 精确匹配 + 租户 claim 提取），**17 个服务（含网关）全部接入**；未配置密钥源时自动降级为 **dev-bypass**（仅校验 Bearer 非空，启动打 WARN）；**RBAC 仍未实现**。`@AuditOperation` 审计切面已实现，出口默认内存 sink、可切 Kafka（`socp.audit.sink=kafka`）；traceId 写入 MDC 并**已在全局 logback pattern 中打印**；多租户 SDK 级强制（TenantFilter + BaseEntity）已实现。

## 目录
```
socp/
├── pom.xml                 # 根 BOM，钉死全部版本（含 §1 版本核验 caveats）
├── platform/               # 10 个横切模块（socp-bom/auth/tenant/audit/ratelimit/obs/error/data/test/rule）
├── services/               # 17 个业务服务（soc-base…incident-web）
├── frontend/               # pnpm monorepo：packages/{library,soc-ui,dev-deps} + apps/{workbench}
├── agents/                 # 端点 Agent（Falco 规则 + Vector 配置）— 配置文件已就位，未与后端联调
├── infra/                  # docker-compose.yml + init-sql（PG 库 + AGE + Kafka topic + ClickHouse + Keycloak + Prometheus）— 编排就绪，未接线
└── docs/                   # 架构/执行顺序说明
```

Maven 模块合计 **27 个**（10 platform + 17 services，含 `socp-bom`），外加 1 个根聚合 POM。

## 模块 ↔ 安全域/P 提示词 映射（§8.1 / §8.2）

> 状态图例：✅ 真实实现（内存态/H2，可运行）· 🟡 骨架或部分实现
> 所有 ✅ 均指**进程内逻辑已实现**，不代表已接入对应中间件。

| 服务 | 端口 | 安全域 | P | 状态 |
|---|---|---|---|---|
| soc-base | 18086 | SOC | P2 | ✅ 租户管理 + 平台概览（内存态） |
| asset-web | 18085 | ASSET | P3 | ✅ 资产管理 CRUD（内存态） |
| asset-collect | 18091 | ASSET | P4 | ✅ 资产/情报采集入口（内存态） |
| alert-web | 18080 | ALERT | P5 | ✅ 告警 CRUD + 查询/下钻（H2 落库） |
| search-config | 18081 | SEARCH | P6 | ✅ 日志源配置 + Vector 渲染 + ingest（H2 落库） |
| detect-web | 18082 | DETECT | P7 | ✅ 规则 CRUD + 引擎热更新 + 背压 503（内存态） |
| detect-model | 18090 | DETECT | P8 | 🟡 内存态二次关联分析（复用 socp-rule 引擎），未接 Kafka 消费/持久化 |
| hips-web | 18087 | HIPS | P9 | ✅ 端点注册/心跳/注销（内存态） |
| hips-collect | 18093 | HIPS | P10 | ✅ Falco/端点事件采集入口（内存态） |
| soar-web | 18083 | SOAR | P12 | ✅ 剧本 CRUD + 启停（内存态，**非** Temporal Saga） |
| report-web | 18084 | REPORT | P13 | ✅ 日报 + 7 日趋势（内存态，**非** ClickHouse） |
| ai-assistant | 18088 | AI | P14 | ✅ 关键词知识库问答（内存态，未接 LLM） |
| api-gateway | 18092 | SPI | P15 | ✅ Spring Cloud Gateway 路由 + 鉴权 + traceId |
| threat-web | 18094 | THREAT | — | ✅ 威胁情报（H2 落库） |
| attack-web | 18095 | ATT&CK | — | ✅ 战术/技术矩阵（内存态） |
| notify-web | 18096 | 通知 | — | ✅ 通知渠道与下发（内存态） |
| incident-web | 18097 | 案件 | — | ✅ 案件工单（H2 落库） |

platform 横切模块 **10 个**：`socp-auth` ✅（JWT 验签 + dev-bypass 回退，无 RBAC）· `socp-tenant` ✅ · `socp-audit` ✅（内存 sink 默认 / Kafka sink 可选）· `socp-ratelimit` ✅ · `socp-error` ✅ · `socp-rule` ✅（规则引擎共享库，含单测）· `socp-data` ✅（`BaseEntity` 多租户基类，仅 1 个类）· `socp-obs` ✅（`TraceIdFilter` + 全局 logback pattern）· `socp-bom`（BOM，无 java）· `socp-test`（测试依赖聚合，无 java）。

前端 **1 个 app**：`apps/workbench`（5173）——统一控制台 Shell，暗色侧边栏切多个模块，`App.vue` 2121 行 + `api.ts` 375 行，真实对接后端 API。
> 历史文档中的 `apps/{alert,search,soar,report}` 四个独立 app 已于 **2026-08-08 清理**，功能折叠进 workbench，**不再存在**。

## 当前限制 / 尚未接线

以下内容在文档或编排中出现，但**代码中并未实现或接线**，请勿据此判断落地度：

| 项 | 声明位置 | 真实状态 |
|---|---|---|
| Kafka | docker-compose | 🟡 **仅审计出口已接线**（`KafkaAuditSink`，需 `socp.audit.sink=kafka` 且 classpath 有 spring-kafka；默认走内存 sink）。DETECT/SEARCH 等业务链路仍零引用 |
| OpenSearch | docker-compose | ⛔ 未接线，SEARCH 检索为进程内实现 |
| ClickHouse | docker-compose | ⛔ 未接线，REPORT 报表为内存态聚合 |
| Redis | docker-compose | ⛔ 未接线，限流用进程内令牌桶 |
| Temporal | docker-compose | ⛔ 未接线，SOAR 无 Saga / 无补偿 |
| Keycloak | docker-compose | 🟡 **验签侧已就绪**（配 `socp.security.issuer-uri` 即可校验其签发的 JWT），但**无 OIDC 登录/授权码流程**，realm 未实跑 |
| PostgreSQL + AGE | docker-compose | ⛔ 未接线，无关联图；关系库用 H2 替身 |
| MinIO | docker-compose | ⛔ 未接线，无对象存储读写 |

其余已知缺口：

- **鉴权：默认 dev-bypass，且无 RBAC**。`socp-auth` 本身已实现真实 JWT 校验（验签 + `exp`/`nbf` + issuer），**17 个服务全部接入**；但**未配置 `socp.security.issuer-uri` / `jwt-secret` 时自动降级为 dev-bypass**——任意非空 Bearer 均放行（启动打 WARN）。当前仓库无任何服务配置密钥源，**开箱即 dev-bypass**。此外**授权（RBAC/`@PreAuthorize`）完全未实现**，任何合法令牌均可访问全部接口。上生产必须显式配置密钥源 + `dev-bypass=false` 并补齐 RBAC。
- **持久化**：仅 4 个服务（alert-web / search-config / threat-web / incident-web）使用 **H2 文件库**作为 PostgreSQL 替身，其余 13 个为**纯内存态**（重启即丢）。无 Flyway/Liquibase 迁移，`ddl-auto: update` 自动建表。
- **可观测性**：`micrometer-registry-prometheus` 已是 **17 个服务的依赖**，但 `management.endpoints.web.exposure.include` **仅 api-gateway 暴露了 `prometheus`**；其余 16 个只开 `health,info`（detect-web / search-config / alert-web 另开 `metrics`），抓取仍会 404 —— 属一行配置的差距。**无 OpenTelemetry**（仅根 pom 声明了 `micrometer-tracing-bridge-otel`，各服务未引入）。traceId 已写入 MDC 并由 socp-obs 的全局 `logback-spring.xml` 打印。
- **测试**：全仓 **20 个测试类**，分布在 8 个模块（`socp-rule` 3、`search-config` 3、`asset-web` 3、`soc-base` 3、`hips-web` 2、`hips-collect` 2、`asset-collect` 2、`soar-web` 2）。均为单元/切片测试，**零跨服务集成测试**，构建默认 `-DskipTests`。
- **CI/CD**：**不存在**任何流水线配置；本目录**也不是 git 仓库**。
- **Docker**：中间件需要 Docker 运行时，部分开发环境不具备；上述服务在无 Docker 环境下可完整运行。

## 两种运行方式

### A. 本沙箱纵切切片（无 Docker 也能跑）
用 H2 作 PostgreSQL 替身、内存审计出口，验证「网关 → 鉴权 → 租户 → 限流 → 审计 → 存储 → 链路追踪」全链路。

**端口**：alert-web `18080`，网关 `18092`（避开旧 SIEM 控制台占用的 8080；可用 `SOCP_SSA_PORT` / `SOCP_GATEWAY_PORT` 覆盖）。

```bash
cd <仓库根>
# 1) 全量构建（27 模块，产出 fat jar）。build/mvnw.sh 已内置阿里云镜像 + 本仓 JDK21
bash socp/build/mvnw.sh -DskipTests package

# 2) 一键起停切片（两个服务后台运行，日志在 .cache/*.log）
bash socp/build/run-slice.sh start
bash socp/build/run-slice.sh stop

# 3) 一键端到端验证（18 项断言：鉴权/写入/多租户/追踪/限流）
python socp/build/verify-slice.py
```

手工验证：
```bash
# 写入告警（occurredAt 可选，不传则取服务端接收时间）
curl -X POST http://localhost:18092/alert-web/api/alarms \
  -H "Authorization: Bearer demo" -H "X-Tenant-Id: t1" \
  -H "Content-Type: application/json" \
  -d '{"ruleId":"AUTH-BRUTE","ruleName":"SSH 暴力破解","severity":"HIGH",
       "message":"失败登录 5 次","entity":"10.0.0.99","occurredAt":"2026-01-15T08:30:00Z"}'

# 查询（限流 10 次/秒/租户，超限返回 HTTP 429 + Retry-After）
curl -i "http://localhost:18092/alert-web/api/alarms?q=10.0.0.99" \
  -H "Authorization: Bearer demo" -H "X-Tenant-Id: t1"
```

切片已验证的横切能力：

| 能力 | 实现 | 表现 |
|---|---|---|
| 鉴权（**dev-bypass 态**） | 网关 `GatewayFilter` + 服务 `AuthInterceptor` 双层，共用 `JwtValidator`；未配密钥源 → dev-bypass，仅判 Bearer 非空 | 无 Bearer → **401** + 统一响应体 + traceId；dev-bypass 下任意非空 token 均放行。配置 issuer/secret 后转为真实验签 |
| 多租户 | `TenantContext` + `BaseEntity.tenantId` | t1 查不到 t2 的告警 |
| 限流 | `@RateLimit` + 内存令牌桶，key 含租户 | 突发 20 次 → 10 通过 / 10 个 **429 + Retry-After**；租户配额互不影响 |
| 链路追踪 | 网关生成/透传 `X-Trace-Id` | 响应头与 `body.traceId` 一致；**被拒请求同样有 traceId** |
| 审计 | `@AuditOperation` 切面 | 默认内存 sink；置 `socp.audit.sink=kafka` 且 classpath 有 spring-kafka 时切到 `socp-audit` topic（本地切片未启用） |
| 存储 | JPA + H2（替身 PG） | 落 `t_alarm`，返回 UUID 主键；`ddl-auto: update`，无迁移脚本 |

> **状态码约定**：命中标准 HTTP 语义的错误码（401/403/404/429/5xx）会映射为真实 HTTP 状态码，
> 让网关统计、Prometheus 告警、客户端退避正常工作；业务自定义码（如 10001）仍返回 HTTP 200，靠 `body.code` 区分。

### B. Docker 机全栈（中间件编排，**Java 侧尚未接线**）
> 起容器只是把中间件跑起来，**当前没有任何 Java 代码会去连它们**。
> 真正切换需要先完成各域的客户端接线任务，届时再按下方注释配置数据源。

```bash
cd socp/infra
docker compose up -d                 # 起 PG+AGE/Kafka/OpenSearch/Redis/ClickHouse/Temporal/Keycloak/MinIO/可观测
# 建库（自动）+ 建 AGE 图（手动，需 AGE 镜像）：执行 init-sql/age/02_age.sql
# 建 Kafka 系统 topic：docker exec -i socp-kafka bash < init-sql/kafka/create-topics.sh
# 业务服务切到 PG：每个服务加 profile/环境变量 spring.datasource.url=jdbc:postgresql://localhost:5432/alert 等
```
> 镜像 tag 钉写稿时已知 GA；正式编码前请联网到官方源核验一次（见 §1 版本核验清单）。已知偏旧项：Temporal(建议 1.24+)、Keycloak(建议 27/28)、ClickHouse(建议 26.x LTS)。

## 版本核验（必读，§1）
联网被拦无法实时核验，文档暂钉写稿时已知 GA 版本。编码前请核对：
Temporal 1.3.0→建议 1.24+；Keycloak 26.0→建议 27/28；ClickHouse 25.3→建议 26.x；TypeScript 5.9→6.0(若 GA)；Java 21(可留 21 或升 25 LTS)；Spring Boot 3.5/Cloud 2025.0 可升 3.6/3.7。其余（Kafka 4.0/PG 18/Redis 8/OpenSearch 2.19/Vue 3.5/Vite 7/pnpm 10）属一年内 GA，可接受。镜像 tag 拉取失败换同系列其它可用 tag 即可。
