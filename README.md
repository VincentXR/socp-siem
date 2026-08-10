# SOCP SIEM

A self-hosted, event-driven SIEM/SOC platform built to explore security telemetry ingestion, detection engineering, alert investigation and automated response.

The focus is the **core pipeline**, not the number of services:

```
Vector / 模拟日志
        │
        ▼
   Ingestion (归一化 · 攒批 · 背压)
   ├──────────────┬──────────────┐
   ▼              ▼              ▼
  Kafka        OpenSearch      PG/H2
 (事件流)      (原始事件检索)   (告警事实源)
   │
   ▼
 Detection Engine (规则引擎 · 热更新 · 幂等 · 抑制)
   │
   ▼
  Alert (富化 · ATT&CK · IOC 命中)
   ├──────────────┬──────────────┐
   ▼              ▼              ▼
 PostgreSQL     ClickHouse     SOAR / Incident
 (t_alarm)     (alarm_detail   (剧本编排 · 自动建案)
                报表聚合)
   │
   ▼
 report-web 日报 / Grafana / 前端控制台 (SSE 实时)
```

外围还有资产、HIPS、ATT&CK 覆盖、通知渠道等模块，但核心是上面这条 **event-driven detection pipeline**——这也是本仓库最值得研究的部分。

---

## 真链路验证（GitHub Actions 可独立复现）

CI 不止构建与单测。`.github/workflows/ci.yml` 的 `e2e-pipeline` job 会真实拉起 4 个中间件
（Kafka / OpenSearch / PostgreSQL / ClickHouse）+ 4 个核心服务，注入 1 条攻击事件并断言整条链路：

| 步骤 | 断言 |
|---|---|
| push 模拟攻击事件（`search-config` 归一化管线） | `accepted=1` |
| Kafka `socp-events` topic | offset 增长 |
| OpenSearch `socp-events-*` | 出现 raw event |
| Detection 命中（sudo 权限提升） | `alert-web` 出现 CRITICAL 告警 |
| PostgreSQL `t_alarm` | 告警已落库（经 API 查询验证） |
| ClickHouse `alert_agg.alarm_detail` | 出现明细记录 |
| `report-web` 日报 API | 200 |

本地等价验证：

```bash
# 前置：docker compose -f infra/docker-compose.yml up -d + 起核心服务
python build/verify-pipeline.py      # 真链路 E2E（12 项断言）
python build/verify-full.py          # 62 项全栈 E2E（健康/全链路/持久化/鉴权/多租户/限流/追踪）
```

## 3 个攻击场景 Demo

`build/demos/attack-scenarios.py` 从「攻击日志」一直演示到「告警 → ATT&CK → 事件建案」：

| 场景 | 攻击日志 | 规则（类型） | ATT&CK | 告警 |
|---|---|---|---|---|
| SSH 暴力破解 | 60s 内同 IP 5 次登录失败 | `AUTH-BRUTE`（threshold） | T1110 Brute Force | HIGH |
| Windows PowerShell 编码命令 | `powershell -enc ...` 内联下载执行 | `EXEC-SUSPICIOUS-SHELL`（pattern，**热更新修正**） | T1059.001 PowerShell | HIGH |
| Linux nginx Web Shell | `/bin/sh` 由 nginx 拉起 / `cmd=whoami` | `WEB-SHELL`（pattern，**API 新建 + 热更新广播**） | T1505.003 Web Shell | CRITICAL |

```
$ python build/demos/attack-scenarios.py
场景 1: SSH 暴力破解（Brute Force）  |  ATT&CK: T1110
  已注入 5 条攻击日志
  [PASS] 检测命中并产生告警（AUTH-BRUTE）  -> HIGH
  告警: [HIGH] 源 203.0.113.77 在 60s 内失败登录 5 次，疑似暴力破解
  [PASS] 告警关联事件（自动建案/归并）
...
攻击场景 Demo 通过 8 / 失败 0
```

其中场景 2/3 同时演示了 **Detection Engineering 的规则生命周期**：规则通过 API 新增/修正，
`RuleChangePublisher` 发 Kafka 广播 → 集群内所有引擎实例热更新（`DetectEngineService.reload()` 原子替换），全程无需重启。

## Detection Engine 做了什么

- **7 种规则类型**：`pattern`（单事件命中）/ `threshold`（滑动窗口计数）/ `correlation` / `correlation-set`（多步关联）/ `baseline`（UEBA 自身历史基线）/ `rare`（首见值）
- **规则生命周期**：CRUD + 热更新（原子替换旧引擎，毒丸退出） + Kafka 广播到多实例
- **背压**：10 万事件队列 + 50ms 缓冲 + 满则丢弃计数，HTTP 接入端据此回 `503 + Retry-After`，Vector 自动重试而不是静默丢数据
- **幂等**：Kafka 消费手动 commit（至少一次）+ `eventId` LRU 去重，重复投递不重复告警
- **抑制去重**：同规则 + 同实体 5 分钟抑制（`Suppressor`），防告警风暴
- **规则健康度**：per-rule 命中/告警统计（`GET /detect-web/api/v1/stats` 的 `ruleStats`），可识别从不命中的死规则
- **MITRE ATT&CK 映射**：每条规则带 `mitre` 字段，`attack-web` 聚合检测覆盖率

## 架构决策（为什么这么做）

| 决策 | 选择 | 理由 / trade-off |
|---|---|---|
| 规则引擎 | 自研进程内（`socp-rule`） | 避免引入 Logstash/Flink 的重依赖；单消费者虚拟线程模型，规则以 JSON 配置表达，热更新友好。窗口状态在内存（单实例语义），多实例需按 key 分区 |
| 采集链路 | Vector + 自研归一化（`search-config`） | 保留对日志格式的完全控制；Vector 只做采集与轻量转发 |
| 事件总线 | Kafka `socp-events`（3 分区） | 解耦采集与检测；consumer 手动 commit + 幂等，可靠性已覆盖（acks=all + DLQ） |
| 检索 | OpenSearch 按天索引 | 原始事件检索/取证；与 PG（告警事实源）职责分离 |
| 报表 | ClickHouse 聚合 | 明细表 `alarm_detail` 服务日报/趋势，避免 OLTP 库扛分析查询 |
| 告警事实源 | PostgreSQL `t_alarm` | 告警生命周期（状态/处置/备注）需要强一致事务 |
| 遥测 | OpenTelemetry SDK + W3C traceparent + Jaeger | 每请求一个 span，Kafka header 透传 trace 上下文；HTTP 与事件流同一 trace 可下钻 |

## 边界（诚实声明）

- **多租户**：当前为 `tenant_id` 列的**逻辑隔离**（SDK 强制写入 + 查询过滤），不是物理隔离（同库同表）
- **ai-assistant**：关键词知识库问答，**未接外部 LLM**——本项目暂不做"为 AI 而 AI"
- **Kafka**：演示环境单 broker（副本因子 1）；生产按集群调整分区/副本
- **SOAR**：剧本执行/重试/补偿在进程内实现（语义等价），未用 Temporal 分布式编排
- **Keycloak**：验签侧可切换 `issuer-uri`（JWKS），OIDC 登录流程未实跑
- 所有服务默认走 `dev` profile（本地账号表）；生产请用环境变量覆盖 `SOCP_JWT_SECRET` / `SOCP_PG_*` 等

## 快速开始（约 15 分钟）

前置：Docker Desktop（已开启虚拟化）、Node.js 22+（仅前端）。

```bash
git clone https://github.com/VincentXR/socp-siem.git && cd socp-siem/socp

# 1) 起 8 个中间件（PG / Kafka / OpenSearch / ClickHouse / Redis / MinIO / Prometheus / Grafana）
docker compose -f infra/docker-compose.yml up -d

# 2) 构建 27 模块（内置阿里云镜像 + JDK21，产出可执行 fat-jar）
bash build/mvnw.sh -DskipTests package

# 3) 起后端服务（端口 18080~18097，日志 .cache/*.log）
bash build/run-all.sh backend

# 4) 前端控制台
cd frontend/apps/workbench && node ../../node_modules/vite/bin/vite.js --port 5188
```

访问：**控制台** http://localhost:5188（demo / demo123）· **Grafana** http://localhost:3000（admin / Socp@2026）
中间件：PG `socp/socp` · OpenSearch `admin/Socp!Sec2026xK` · ClickHouse `default/socp`

> ⚠️ 凭据为演示用途，生产请用环境变量覆盖。

## 目录结构

```
socp/
├── platform/       # 横切模块：auth(JWT) tenant audit ratelimit error obs(OTel) rule(引擎) client(服务间调用) bom test
├── services/       # 业务服务（网关 / 采集 / 检测 / 告警 / SOAR / 报表 / 案件 / 资产 / HIPS / 情报 / ATT&CK / 通知）
├── frontend/       # workbench 控制台（Vue3 + Element Plus + ECharts）
├── infra/          # docker-compose + init-sql（8 中间件 + jaeger/keycloak/temporal 可选）
├── build/          # 构建脚本 + verify-full / verify-pipeline / demos/attack-scenarios
├── .github/        # CI：build + test + e2e（切片）+ e2e-pipeline（真链路）
└── docs/
```

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway 2025.0 · Kafka · OpenSearch · ClickHouse · PostgreSQL · Redis ·
OpenTelemetry + Jaeger · Prometheus + Grafana · Vue 3 + Vite + Element Plus + ECharts · GitHub Actions
