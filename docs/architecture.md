# SOCP 企业级 SIEM 平台 —— 架构

> 本文档描述目标架构与当前落地状态。状态会随开发推进更新（见 module-map.md）。

## 0. 一句话
链路不变（采集→检测→分析→响应→报表），存储/引擎/多租户/可靠性四方向重构为统一平台。
单仓 `socp/`，中间件全 docker-compose，端点采集交 Vector/Falco/OTel。

## 0.1 三横层
```
┌ 接入/端点层
│  ├─ Keycloak 26（人机 OIDC PKCE）· Spring Authorization Server（机机 client-credentials）
│  │     [🟡 验签侧已就绪：JwtValidator 支持 JWKS 拉公钥 + exp/nbf + issuer 校验；
│  │      但无 OIDC 登录/授权码流程，Keycloak 容器未实跑，默认走 dev-bypass]
│  ├─ api-gateway（Spring Cloud Gateway）── 统一北向入口 :18092  [✅ 已实现，18/18 验证]
│  ├─ 前端 1 app：apps/workbench（:5173）· 3 packages           [✅ 真实实现]
│  │     └─ 统一控制台 Shell（App.vue 2121 行 + api.ts 375 行），真实对接后端 API
│  │     └─ 历史文档中的 alert/search/soar/report 四个独立 app 已于 2026-08-08 清理，已折叠进 workbench
│  └─ 端点 Agent（非 Java）：Falco（运行时）· Vector（日志）
│        └─ agents/ 目录 [🟡 配置文件已就位（vector-pipeline + falco-rules），未与后端联调]
│        └─ OTel Collector [⛔ 规划：既不在 docker-compose 中，代码也无 OTel 依赖]
│
┌ 业务服务层 ── 17 个 Java 21 服务（容器内 8080 / 宿主 18080~18097，context-path /{服务名}）
│   SOC:soc-base ✅ │ ASSET:asset-web ✅·asset-collect ✅ │ ALERT:alert-web ✅ │ SEARCH:search-config ✅
│   DETECT:detect-web ✅·detect-model 🟡(内存态二次关联，未接 Kafka) │ HIPS:hips-web ✅·hips-collect ✅ │ SOAR:soar-web ✅
│   REPORT:report-web ✅ │ AI:ai-assistant ✅ │ SPI:api-gateway ✅
│   THREAT:threat-web ✅ │ ATT&CK:attack-web ✅ │ 通知:notify-web ✅ │ 案件:incident-web ✅
│   ※ ✅ = 进程内逻辑已实现（内存态 / H2），不代表已接入中间件
│   └─ 横切 platform/ 10 模块：socp-{auth,tenant,audit,ratelimit,obs,error,data,test,bom,rule}
│         [auth/tenant/audit/ratelimit/error/rule/data/obs ✅ · bom/test 无 java]
│         [auth：JWT 验签已实现，但默认 dev-bypass 且无 RBAC；audit：内存 sink 默认 / Kafka sink 可选]
│
└ 中间件层 ── docker-compose 官方镜像一键起 [🟡 编排齐全，Java 侧基本零接线、未实跑]
    PostgreSQL 18(+AGE) · Kafka 4.0 · OpenSearch 2.19 · Redis 8 · ClickHouse 25
    · Keycloak 26 · MinIO · Temporal · Prometheus/Grafana/Loki/Jaeger
    ※ 除 Kafka（仅审计出口 opt-in）与 Keycloak（仅验签侧就绪）外，其余中间件在 Java 代码中
      **无任何客户端引用**，服务默认内存态 / H2 运行
```

## 0.2 主链路（**目标架构 —— 中间件尚未在代码中接线**）

> ⚠️ 下图描述的是**设计目标**，不是当前实现。图中所有中间件（OpenSearch / Kafka / PostgreSQL+AGE /
> Temporal / ClickHouse）**均未在 Java 代码中接线**，仅存在于 docker-compose 编排里。

```
Vector/Falco Agent ─▶ SEARCH 管道 ─▶ OpenSearch（检索/下钻）              ← 采集/检索 [⛔ 未接线]
GASWeb 规则匹配引擎 ─▶ Kafka socp-detect-* ─▶ GASModel 窗口聚合
               ─▶ PostgreSQL t_alarm + AGE 关联图                      ← 检测/分析 [⛔ 未接线]
socp-detect-analyzed-alarm ─▶ SOAR（Temporal Saga + 补偿）               ← 响应 [⛔ 未接线]
                        └▶ REPORT（ClickHouse 报表聚合）                  ← 报表 [⛔ 未接线]
ALERT 看板 ─▶ PG 告警 + ClickHouse 预聚合；下钻 ─▶ SEARCH(OpenSearch)/DETECT(AGE)  [⛔ 未接线]
```

**当前实际链路（内存态纵切）**：

```
HTTP ingest ─▶ search-config（进程内解析/渲染，H2 落配置）
detect-web（进程内规则引擎，内存态告警）─▶ detect-model（🟡 HTTP 收告警 → socp-rule 引擎二次关联，非 Kafka 消费）
alert-web（H2 t_alarm，告警 CRUD/查询/下钻）◀─ workbench 前端
soar-web / report-web / ai-assistant（均为进程内内存态实现）
                 ↑ 全链路无消息队列、无检索引擎、无工作流引擎
```

## 0.3 与原系统硬替换

> 「定稿方案」列是**决策结论**，不等于已完成。第三列标注当前落地度。

| 原自研/旧实现 | 定稿方案 | 当前落地度 |
|---|---|---|
| PQL 检索引擎 | OpenSearch 原生 DSL | ⛔ 未接线（SEARCH 检索为进程内实现） |
| Flink 流式检测 | Kafka 窗口聚合 + GASWeb 规则引擎 | 🟡 规则引擎 ✅；Kafka 窗口聚合 ⛔ |
| NebulaGraph 图 | Apache AGE（PG 扩展） | ⛔ 未接线 |
| Java HipsAgent | Falco + Vector | 🟡 配置就位，未联调 |
| ES 7.10.2 服务端 | OpenSearch 2.19 统一 | ⛔ 未接线 |
| 自研 SOAR 执行 | Temporal Saga | ⛔ 未接线（soar-web 为内存态 CRUD） |
| 告警存 GaussDB 手写 SQL | PostgreSQL alert.t_alarm | 🟡 已用 JPA，但库是 H2 替身；无迁移脚本 |
| 自研 APIGateway MVC | Spring Cloud Gateway | ✅ 已实现 |
| Kafka acks=1/不重试 | acks=all + 幂等 + RetryTopic + DLT | 🟡 仅审计出口有 `KafkaAuditSink`（opt-in）；acks/幂等/RetryTopic/DLT 均未配置 |
| 多租户靠业务约束 | SDK 强制（拦截器 + 各级前缀） | ✅ TenantFilter + BaseEntity 已实现 |
| 自研鉴权 | Keycloak OIDC + RBAC | 🟡 认证：JWT 验签已实现（JWKS/HMAC + exp/nbf + issuer），17 服务全接入，但默认 dev-bypass；授权：**RBAC 未实现** |

## 0.4 已知缺口（简表）

| 维度 | 现状 |
|---|---|
| 鉴权 | 认证已实现（JWT 验签 + exp/nbf + issuer + 租户 claim），17 服务全接入；但仓库内无服务配置密钥源 → **开箱即 dev-bypass**（任意非空 Bearer 放行）。**授权 RBAC 完全未实现** |
| 持久化 | 4 服务用 H2 文件库（alert-web/search-config/threat-web/incident-web），13 服务纯内存；无 Flyway/Liquibase，`ddl-auto: update` |
| 可观测 | `micrometer-registry-prometheus` 已进 17 服务依赖，但**仅 api-gateway 暴露 `/actuator/prometheus`**，其余 16 个只开 `health,info`（3 个另开 `metrics`）→ 抓取 404；无 OTel；traceId 已在 socp-obs 全局 logback pattern 中打印 |
| 测试 | 20 个测试类 / 8 个模块（socp-rule 3、search-config 3、asset-web 3、soc-base 3、hips-web 2、hips-collect 2、asset-collect 2、soar-web 2），零跨服务集成测试，构建默认 `-DskipTests` |
| CI/CD | 不存在；本目录也不是 git 仓库 |

详细逐模块状态见 `module-map.md`。

## 战略边界（com.siem vs SOCP）
`com.siem` 是自研单进程引擎，已端到端验证（含 Vector 旁路）。本轮决定：**将其已验证能力迁进 socp**
（解析链、规则引擎、事件模型作为 SEARCH/DETECT 底座），com.siem 保留为参考实现直至 socp 链路完整。
详见 migration-comsiem-to-socp.md。
