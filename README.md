# SOCP SIEM

A self-hosted, event-driven SIEM/SOC platform built to explore security telemetry ingestion, detection engineering, alert investigation and automated response.

**核心是 Security Event Pipeline，不是服务数量：**

```
Telemetry ingestion → Event normalization (Canonical Schema) → Kafka streaming
→ Detection engineering (7 种规则类型 · 热更新 · 幂等 · 去重) → Alert lifecycle
→ Incident workflow → Automated response (SOAR 剧本 · 补偿)
```

能力清单：

1. **Telemetry ingestion** — Vector / NDJSON 接入，攒批 + 背压（503 + Retry-After）
2. **Event normalization** — Parser Pipeline（JSON / Syslog / CEF / LEEF / KV / Sysmon / auditd / Falco）→ **Canonical Event Schema**（厂商无关字段模型）
3. **Kafka-based event streaming** — `search-config → Kafka socp-events → detect-web` 唯一主链（acks=all · 幂等 · 手动 commit · DLQ）
4. **Detection engineering** — threshold / pattern / correlation / UEBA baseline / rare 规则，窗口去重 + 抑制，规则生命周期（DRAFT→TESTING→ACTIVE→DISABLED→ARCHIVED）
5. **Alert lifecycle** — 告警持久化（PG）+ 富化（IOC / ATT&CK）+ 状态流转（OPEN→INVESTIGATING→RESOLVED→CLOSED）
6. **Incident workflow** — 告警自动建案/归并（UUIDv7 主键 + INC- 展示编号）
7. **Automated response** — SOAR 剧本（通知 / 建案 / webhook），失败重试 + 补偿，执行状态机（SUCCESS / COMPENSATING / FAILED）

Implemented with: Spring Boot · Kafka · OpenSearch · PostgreSQL · ClickHouse · Vector · OpenTelemetry · Vue 3

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

## 日志解析 Pipeline（Canonical Event Schema）

归一化不再是"JSON 展平 + 顺序扫规则"。`search-config` 现在有独立的 parser 层（`services/search-config/.../parser/`）：

```
Raw Event
   ↓
Source Router（vendor 提示 / CEF: / LEEF: / <PRI> / {json} / key=value 特征前缀路由，
            不是每条日志遍历全部规则）
   ↓
ParserRegistry
├─ JsonParser      JSON 行展平 + canonical 别名映射（Falco / Suricata / 采集器包装行）
├─ SysmonParser    Windows Sysmon JSON（EventID 1/3/11/22 → process/network/file 语义）
├─ SyslogParser    RFC3164 / RFC5424（PRI → severity，host / app / pid / message）
├─ CefParser       ArcSight CEF（SignatureID → event.code，Extension → source/destination）
├─ LeefParser      QRadar LEEF（同 CEF 对齐）
├─ KvParser        key=value（引号支持）
└─ RegexRuleParser 用户 parse rules（兜底抽取，命中即停）
   ↓
Canonical Event Schema（简化 ECS）
   ↓
Enrichment（查找表：核心资产 / 关键人员 / 封禁名单）
   ↓
Kafka socp-events → Detection
```

**Canonical 字段模型**（Detection Rule 用这些键写 match，不关心厂商）：

```
event.code / event.category / event.type / event.action
source.ip / source.port / destination.ip / destination.port
host.name / user.name
process.name / process.pid / process.command_line
file.path / file.hash.sha256
network.protocol
```

例：Sysmon `EventID 1`（进程创建）、Falco JSON、FortiGate CEF firewall 日志，
经过各自 parser 后都产出 `event.category=process` / `source.ip` / `user.name` 等同一组字段——
检测规则只需写一次，厂商差异被 parser 层吸收。兼容层同时把 `src_ip`/`user`/`host` 等
原有键桥接回 `fields`，存量规则无需改动。

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

## 两种运行模式（诚实接线状态）

不是"假装全上中间件"。默认就是**混合模式**，起 compose 后核心链路立即生效：

| 服务 | 默认（Local Dev） | 集成（Integration） |
|---|---|---|
| alert-web / threat-web / incident-web / soc-base | **PostgreSQL**（alert/threat/incident/audit 库，Flyway 迁移） | 同左 |
| search / detect / soar / asset / hips / notify / attack / ai / detect-model | **H2 文件库**（`~/.socp/*.mv.db`，内存+库双写，重启不丢） | 同左（H2 即持久化） |
| gateway / report / 采集器（asset-collect / hips-collect） | 无状态（网关路由 / 查 CK+PG / 上报） | 同左（无状态不需要 DB） |
| Kafka `socp-events` / `socp-audit` / `socp-alarm-original` | **search-config → detect-web 主链** + 审计 + 二次分析（compose 起后生效） | 同左 |
| OpenSearch `socp-events-*` | **search-config 写 + 读**（检索优先 OS，回退 H2） | 同左 |
| ClickHouse `alarm_detail` | **alert-web 写入**（compose 起后生效） | 同左 |

**Local Dev**（无 Docker）：`bash build/run-all.sh backend` —— 4 个 PG 服务需要本机 PG 或改 H2 profile；
Kafka/OS/CK 链路不生效（detect-web 收不到事件），其余功能完整。

**Integration**（`docker compose -f infra/docker-compose.yml up -d` + `run-all.sh backend`）：
Kafka/OpenSearch/ClickHouse 立即接入，`verify-pipeline.py` 12 项真链路断言可跑。

> 诚实说明：**13 个有状态服务全部持久化**（PG 4 + H2 9，H2 为内存+库双写、重启自动恢复）；
> 4 个无状态服务（网关/报表/采集器）正确无库。PG 与 H2 均为真客户端（Flyway 迁移真实执行）。

## 边界（诚实声明）

- **多租户**：当前为 `tenant_id` 列的**逻辑隔离**（SDK 强制写入 + 查询过滤），不是物理隔离（同库同表）
- **ai-assistant**：关键词知识库问答，**未接外部 LLM**——本项目暂不做"为 AI 而 AI"
- **Kafka**：演示环境单 broker（副本因子 1）；生产按集群调整分区/副本
- **SOAR（2026-08-12 双模式）**：Temporal 可达（`docker compose --profile extra up -d temporal`）走 Workflow/Activity 分布式编排；不可达自动回退进程内执行器（双模式，绝不因编排中间件故障拖垮告警响应）
- **Keycloak（2026-08-12 OIDC 已实跑）**：PKCE 授权码登录（`socp-spa` 客户端，`docker compose --profile extra up -d keycloak`），Keycloak 只作身份源，网关回调统一签发 HS256 session token，业务服务保持 HMAC 验签；`/auth/login` demo 账号保留。验签侧亦可切 `issuer-uri`（JWKS）
- **采集（2026-08-12 真实链路）**：`build/run-vector.sh` 起 Docker Vector 采集真实文件（`demo/sample.log`）+ syslog TCP 5514 → search-config ingest（机机 token 鉴权）→ canonical 解析 → OpenSearch/Kafka → 检测；asset-collect/hips-collect 把上报事件真转发进同一主链。Falco 规则（`agents/falco-rules`）配置就绪，Windows 下未真跑（仅 Linux）
- **RBAC**：管理写端点统一 `@RequireRole(admin/analyst)`（规则/接入配置/剧本/渠道/处置/租户/端点等 15 个控制器），viewer 只读；采集/机机端点（ingest/collect/evaluate/notify）与登录端点豁免
- 所有服务默认走 `dev` profile（本地账号表）；生产请用环境变量覆盖 `SOCP_JWT_SECRET` / `SOCP_PG_*` / `SOCP_OIDC_*` 等

## 快速开始（约 15 分钟）

前置：Docker Desktop（已开启虚拟化）、Node.js 22+（仅前端）。

```bash
git clone https://github.com/VincentXR/socp-siem.git && cd socp-siem

# 1) 起 8 个中间件（PG / Kafka / OpenSearch / ClickHouse / Redis / MinIO / Prometheus / Grafana）
docker compose -f infra/docker-compose.yml up -d

# 2) 构建 27 模块（内置阿里云镜像 + JDK21，产出可执行 fat-jar）
bash build/mvnw.sh -DskipTests package

# 3) 起后端服务（端口 18080~18097，日志 .cache/*.log）
bash build/run-all.sh backend

# 4) 前端控制台（start-frontend.sh 用 cd -P 解析物理路径启动，
#    规避 vite 6.4.3 在 junction 路径下依赖改写失效导致白屏的问题）
bash build/start-frontend.sh            # 默认 5173，可传端口参数
```

访问：**控制台** http://localhost:5173（demo / demo123）· **Grafana** http://localhost:3000（admin / Socp@2026）
中间件：PG `socp/socp` · OpenSearch `admin/Socp!Sec2026xK` · ClickHouse `default/socp`

> ⚠️ 凭据为演示用途，生产请用环境变量覆盖。

## 目录结构

```
socp-siem/
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
