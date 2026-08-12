# 模块地图与落地度

> ## ⚠️ 本文档已过期（最后同步 2026-08-09）
>
> 下表反映 2026-08-08/09 状态，**与当前代码不符**。变化要点（详见 README.md 与代码）：
> - **持久化**：文末"13 服务纯内存"已过时 → 13/13 有状态服务全持久化（PG 4 + H2 9 双写）；2026-08-12 起 9 个 H2 服务可切 PG（`application-pg.yml`）
> - **鉴权**："无 RBAC"已过时 → 15 控制器 43 写端点 `@RequireRole(admin/analyst)`；dev-bypass 默认 false（JWT HMAC 全链验签）
> - **中间件接线**：文末表"Java 代码接线 ⛔ 无"已过时 → Kafka/OpenSearch/ClickHouse 全接线（见 README「两种运行模式」）
> - **CI**："无 CI、非 git 仓库"已过时 → `.github/workflows/ci.yml` 三 job 存在
> - **2026-08-12 新增**：真实采集链路（Vector/collect 转发）、SOAR Temporal 双模式、Keycloak OIDC PKCE 登录
>
> **当前真相源：`README.md` 与仓库代码**；本文件保留历史逐模块记录供追溯。

> 状态图例：✅ 真实实现 · 🟡 部分/占位/骨架 · ⛔ 未实现或未接线 · ❌ 缺失
>
> **重要口径**：本文中的 ✅ 一律表示「**进程内逻辑已实现、服务可运行**」，
> **不代表**已接入 Kafka / OpenSearch / ClickHouse / Temporal 等任何中间件。
> 中间件接线情况见文末「中间件接线状态」。
>
> java 数为 `src/main/java` 下 `.java` 文件实测计数（不含测试）。

## platform/（10 个横切模块）

| 模块 | java 数 | 测试数 | 状态 | 说明 |
|---|---|---|---|---|
| socp-auth | 6 | 0 | ✅ | `JwtValidator`（nimbus-jose-jwt：JWKS 非对称 / HMAC 对称验签 + `exp`/`nbf` + issuer 精确匹配 + 租户 claim 提取）+ `AuthInterceptor`。**17 个服务全部依赖它**。⚠️ 未配密钥源时降级 **dev-bypass**（仅判 Bearer 非空）；**无 RBAC** |
| socp-tenant | 2 | 0 | ✅ | TenantFilter + MyBatis 拦截器 |
| socp-audit | 7 | 0 | ✅ | `@AuditOperation` 切面 + 双 sink：`InMemoryAuditSink`（默认）/ `KafkaAuditSink`（`socp.audit.sink=kafka`，spring-kafka 为 optional 依赖） |
| socp-ratelimit | 4 | 0 | ✅ | TokenBucket（进程内）+ 拦截器；未接 Redis，多实例不共享配额 |
| socp-error | 3 | 0 | ✅ | ApiException + GlobalExceptionHandler（429 等映射为真实状态码） |
| socp-rule | 19 | 3 | ✅ | 规则引擎共享库（com.siem 迁移），被 detect-web / detect-model 复用 |
| socp-data | 1 | 0 | ✅ | `BaseEntity`（多租户 `tenantId` 基类），被 alert-web `Alarm` 继承。体量小但非占位 |
| socp-obs | 1 | 0 | ✅ | `TraceIdFilter`（写 MDC）+ `logback-spring.xml` 全局日志格式（含 `%X{traceId}`），随依赖进入 16 个服务 |
| socp-bom | 0 | 0 | — | 依赖 BOM（预期无 java） |
| socp-test | 0 | 0 | — | 测试依赖聚合（预期无 java） |

## services/（17 个业务服务）

| 服务 | 端口 | java 数 | 测试数 | 状态 | 说明 |
|---|---|---|---|---|---|
| alert-web | 18080 | 9 | 0 | ✅ | 告警 CRUD + 查询/下钻，**H2 文件库**落 `t_alarm`；实体继承 socp-data `BaseEntity` |
| search-config | 18081 | 37 | 3 | ✅ | 日志源配置 + Vector 渲染 + ingest 接收 + SPL 引擎，**H2 文件库**；带单测 |
| detect-web | 18082 | 11 | 0 | ✅ | 规则 CRUD + 引擎热更新 + 背压 503 + 告警/统计（内存态） |
| soar-web | 18083 | 8 | 2 | ✅ | 剧本 CRUD + 启停（内存态）。**非 Temporal Saga**，无补偿/无持久化工作流 |
| report-web | 18084 | 6 | 0 | ✅ | 日报 + 7 日趋势（内存态聚合）。**非 ClickHouse** |
| asset-web | 18085 | 5 | 3 | ✅ | 资产管理 CRUD（内存态，含种子数据） |
| soc-base | 18086 | 6 | 3 | ✅ | 租户管理 + 平台概览 + 合规（内存态，含种子租户） |
| hips-web | 18087 | 5 | 2 | ✅ | 端点注册/心跳/注销（内存态，含种子端点） |
| ai-assistant | 18088 | 5 | 0 | ✅ | 自然语言安全问答（关键词知识库，内存态）。未接任何 LLM / LangChain4j |
| detect-model | 18090 | 3 | 0 | 🟡 | HTTP `/analyze` 收原始告警 → 复用 **socp-rule 引擎**做二次关联（`accept`/`drain`），结果存 `CopyOnWriteArrayList`。聚合逻辑真实，但**非 Kafka 消费、无持久化、无 AGE 关联图** |
| asset-collect | 18091 | 3 | 2 | ✅ | 资产/情报采集入口（内存态） |
| api-gateway | 18092 | 2 | 0 | ✅ | Spring Cloud Gateway 路由 + `JwtValidator` 验签 + traceId 生成/透传 + 租户透传 |
| hips-collect | 18093 | 3 | 2 | ✅ | Falco/端点事件采集入口（内存态） |
| threat-web | 18094 | 6 | 0 | ✅ | 威胁情报（IOC）管理，**H2 文件库** |
| attack-web | 18095 | 5 | 0 | ✅ | ATT&CK 战术/技术矩阵（内存态） |
| notify-web | 18096 | 6 | 0 | ✅ | 通知渠道配置与下发（内存态） |
| incident-web | 18097 | 8 | 0 | ✅ | 安全案件/工单，**H2 文件库** |

**持久化分布**：4 个服务用 H2 文件库作 PostgreSQL 替身（alert-web、search-config、threat-web、incident-web），
其余 **13 个服务为纯内存态**（重启即丢数据）。全仓**无 Flyway/Liquibase**，建表靠 `spring.jpa.hibernate.ddl-auto: update`。

**鉴权分布**：**17 个服务全部**在 pom 中依赖 `socp-auth`（含 api-gateway，网关侧复用零 Web 依赖的 `JwtValidator`）。
认证链路本身完整：验签（JWKS/HMAC）→ `exp`/`nbf` → issuer → 租户 claim → `TenantContext`。

> ⚠️ **两个必须知道的口子**：
> 1. **默认 dev-bypass**：仓库内**没有任何服务配置** `socp.security.issuer-uri` / `jwt-secret`，
>    `JwtValidator` 因此降级为 dev-bypass —— 任意非空 Bearer 均放行（启动打 WARN）。
>    生产须显式配密钥源并设 `socp.security.dev-bypass=false`。
> 2. **无授权（RBAC）**：`AuthInterceptor` 注释里 `@PreAuthorize` 仍是"在此扩展"的 TODO，
>    任何通过认证的令牌都能访问全部接口。

## agents/（端点 Agent，非 Java）

| 目录 | 状态 | 说明 |
|---|---|---|
| vector-pipeline | 🟡 | vector.toml + README 已就位，未与 search-config 端到端联调 |
| falco-rules | 🟡 | falco_rules.yaml 起点已就位，未与 hips-collect 端到端联调 |

## frontend/（pnpm monorepo）

| 项 | 状态 | 说明 |
|---|---|---|
| apps/workbench (5173) | ✅ | **唯一的前端应用**。统一控制台 Shell，暗色侧边栏切多模块（概览/告警/检索/接入/元数据/规则/编排/报表/资产/端点/AI）。`App.vue` 2121 行 + `api.ts` 375 行，真实对接后端 API |
| packages/soc-ui | ✅ | SeverityTag 等组件（真实） |
| packages/library | ✅ | formatInstant / timeAgo / severityColor 工具（真实） |
| packages/dev-deps | 🟡 | 占位（共享 devDependencies） |

> **历史勘误**：早期文档记载的 `apps/alert` (5174)、`apps/search` (5175)、`apps/soar` (5176)、`apps/report` (5177)
> 四个独立 app **已于 2026-08-08 清理删除**，功能全部折叠进 `apps/workbench`。
> 当前 `frontend/apps/` 下**只有 workbench 一个目录**，前端也**只占用 5173 一个端口**。

> 前端构建注：`frontend/.npmrc` 用 npmmirror；scaffold 早期钉的 typescript 5.9.0 / vite 7.0.0 / vue-tsc 2.1.0 为**虚构版本**，
> 已修为真实版（TS 5.9.3 / Vite 6.4.3 / vue-tsc 3.3.9 / vue 3.5.41 / element-plus 2.14.3）。

## infra/

- 🟡 `docker-compose.yml`：12 个中间件容器编排齐全，**但 Java 侧零接线，且未实跑过**。需要 Docker 运行时（部分开发环境不具备）。
- 🟡 `init-sql/`：pg / age / clickhouse / kafka / keycloak / prometheus 脚本已就位，同样未实跑。
- 🟡 `init-sql/prometheus/prometheus.yml`：端口→服务映射已与 `build/run-all.sh` 校准。
  `micrometer-registry-prometheus` 已是 17 个服务的依赖，但 **`/actuator/prometheus` 目前只有 api-gateway 暴露**
  （其余 16 个 `exposure.include` 仅 `health,info`，detect-web/search-config/alert-web 另开 `metrics`），抓取会 404。
  另注：16 个业务服务带 `context-path`，其指标路径是 `/{服务名}/actuator/prometheus`，与网关的根路径不同，
  故 prometheus.yml 已按「网关 + 业务服务」拆成两个 job。

### 中间件接线状态

| 中间件 | docker-compose | Java 代码接线 | 替代实现 |
|---|---|---|---|
| PostgreSQL (+AGE) | ✅ 已声明 | ⛔ 无 | H2 文件库（4 服务）/ 内存（13 服务）；无 AGE 图 |
| Kafka | ✅ 已声明 | 🟡 **仅审计出口** | `KafkaAuditSink` 已实现（`socp.audit.sink=kafka` 开启，spring-kafka 为 optional）；默认内存 sink。DETECT/SEARCH 业务链路仍无消息队列 |
| OpenSearch | ✅ 已声明 | ⛔ 无 | SEARCH 进程内检索 |
| ClickHouse | ✅ 已声明 | ⛔ 无 | REPORT 内存态聚合 |
| Redis | ✅ 已声明 | ⛔ 无 | 进程内令牌桶限流 |
| Temporal | ✅ 已声明 | ⛔ 无 | soar-web 内存态 CRUD |
| Keycloak | ✅ 已声明 | 🟡 **仅验签侧** | `JwtValidator` 可从 Keycloak JWKS 拉公钥验签；但无 OIDC 登录/授权码流程，realm 未实跑，默认 dev-bypass |
| MinIO | ✅ 已声明 | ⛔ 无 | 无对象存储读写 |

## 工程化现状

| 维度 | 现状 |
|---|---|
| Maven 模块 | **27 个**（10 platform + 17 services，含 socp-bom）+ 1 个根聚合 POM |
| 测试 | **20 个测试类 / 8 个模块**（socp-rule 3、search-config 3、asset-web 3、soc-base 3、hips-web 2、hips-collect 2、asset-collect 2、soar-web 2）。均为单元/切片测试，**零跨服务集成测试**，构建默认 `-DskipTests` |
| 可观测 | `micrometer-registry-prometheus` 已进 17 个服务依赖，但仅 api-gateway 暴露 `/actuator/prometheus`；无 OTel（根 pom 声明了 `micrometer-tracing-bridge-otel`，各服务未引入）；traceId 已由 socp-obs 全局 logback pattern 打印 |
| CI/CD | **不存在**任何流水线配置；本目录**也不是 git 仓库** |

## 落地度小结

- **17/17 后端服务可运行**（detect-model 未接 Kafka/持久化），**10/10** platform 模块就位且均为真实实现（bom/test 无 java 属预期）。
- **前端 1/1 app**（workbench）真实且完整。
- **集群无关**：所有服务内存态 / H2 运行，无需 docker 即可全栈联调 —— 这是当前唯一可用的运行方式。
- **待接线**：OpenSearch / ClickHouse / Redis / Temporal / MinIO / PG+AGE 六类完全未接线；
  Kafka 仅审计出口、Keycloak 仅验签侧。
- **四大缺口**：① 授权 RBAC 未实现且默认 dev-bypass；② `/actuator/prometheus` 仅网关暴露、无 OTel；
  ③ 零跨服务集成测试；④ 无 CI/CD、非 git 仓库。
