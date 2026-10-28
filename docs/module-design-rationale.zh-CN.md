# SIEM 模块设计、工具选型与验收边界

本文说明仓库实现的职责和选型理由，供架构评审及部署验收使用，不是生产准入证书。
模块清单以 [module-map](module-map.md) 和 [运行拓扑](../build/runtime-topology.json) 为准；
具体执行命令以 [验证矩阵](validation-matrix.md) 为准。测试代码存在、静态合同通过、
真实依赖集成通过和生产验收通过是四种不同证据，不能互相替代。

## 业务模块

后端以 Java 21、Spring Boot、Flyway 为共同基础；表中的 PostgreSQL 指生产持久化方案，
H2 仅是开发便利。每个模块仍须通过租户隔离、授权拒绝、持久化恢复和容量验证。

| 模块 / 源码入口 | 设计与工具 | 合理性及代价 | 生产验收重点 |
|---|---|---|---|
| [api-gateway](../services/api-gateway) | Spring Cloud Gateway/WebFlux；JWT/JWKS、Redis、请求签名 | 统一路由和会话边界；业务服务仍独立鉴权。响应式网关需避免阻塞调用，Redis 是正确性依赖 | 企业 IdP、代理信任、证书/密钥轮换、会话撤销、跨副本防重放 |
| [search-config](../services/search-config) | 规范化事件、RE2/J 解析、JPA/PostgreSQL Ingestion Outbox、Kafka、OpenSearch；API/worker 分离 | 解析与检测解耦；事务保存发布意图，搜索故障不阻塞检测。代价是最终一致性和索引重放运维 | 真实采集源、积压与恢复、分片/副本实际分配、索引留存、磁盘水位、检索 SLO |
| [detect-web](../services/detect-web) | 分区 lane 引擎、socp-rule、Kafka 维度路由、PostgreSQL journal/lease/fencing/outbox；内嵌独立持久化单元的二次分析 | 将计算、恢复与结果提交边界显式化；跨维度路由支撑关联检测。自建状态协议维护成本高，不能只靠单实例锁 | 三实例 rebalance、租约过期、快照重放、乱序窗口、规则更新、精确告警集合与跨租户负例 |
| [alert-web](../services/alert-web) | PostgreSQL 告警事实、事务 Outbox 和逐目的地 delivery receipt；Kafka 对账 | 告警事实和分发意图同事务，失败可重试；至少一次投递要求目的地幂等 | 重复投递、claim 超时回收、DEAD 可见与人工重放、全目的地独立故障恢复 |
| [incident-web](../services/incident-web) | PostgreSQL 案件、合并和时间线 | 案件生命周期适合事务数据库，与原始日志检索分开 | 并发处置/合并、来源告警追溯、案件权限和业务流程验收 |
| [soar-web](../services/soar-web) | Temporal、PostgreSQL 执行投影、版本化 playbook、人工审批、S3 兼容产物 | 长任务和等待不依赖单 JVM 生命周期；工作流重放不保证外部动作天然幂等 | Temporal HA、真实连接器超时/重试、动作幂等、审批、防越权、凭据轮换 |
| [notify-web](../services/notify-web) | 持久化分发状态、SMTP/Slack/Webhook 适配器 | 将通知重试与告警事务隔离；远端已收但响应丢失仍有重复风险 | 厂商沙箱正向投递、限流、超时、重放、外部 HTTP 边界及失败告警 |
| [report-web](../services/report-web) | ClickHouse 聚合、S3/MinIO 归档 | 列存扫描避免干扰 OLTP；必须按租户和逻辑告警身份去重，不假定物理写入只发生一次 | 留存、查询延迟、重复数据对账、备份恢复、对象存储支持与不可变保留策略 |
| [soc-base](../services/soc-base) | PostgreSQL 租户、审计、合规与总览查询 | 统一治理事实和审计入口；展示合规条目不等于取得合规认证 | 审计完整性、访问控制、长期保留、防篡改及导出追溯 |
| [asset-web](../services/asset-web) | PostgreSQL 资产、导入与采集入口 | 资产身份为告警补充业务上下文；外部 CMDB 一致性需要同步规则 | 真实 CMDB、资产身份去重、过期数据、批量导入和租户归属 |
| [hips-web](../services/hips-web) | PostgreSQL 终端注册/心跳/事件、持久化转发与历史清理 | 接收与下游投递分离，支持恢复；控制面注册不等于具备完整 EDR 防护能力 | 真机 Agent、注册凭据、断网重连、补发去重、终端历史保留 |
| [threat-web](../services/threat-web) | PostgreSQL IOC、STIX 导入、TAXII 同步及 checkpoint | 标准化情报交换便于接入，checkpoint 支撑增量恢复；数据质量由来源决定 | 真实 feed、认证/分页、断点恢复、过期/置信度策略、升级迁移 |
| [attack-web](../services/attack-web) | PostgreSQL ATT&CK 目录及规则覆盖关系 | 技术分类利于内容治理；覆盖映射不是实际检测有效性的证明 | 内容版本升级、映射完整性、真实攻击正例和正常行为负例 |
| [ai-assistant](../services/ai-assistant) | 证据组合、可选 LLM、确定性降级、PostgreSQL | 保持证据引用与人工审批边界，模型故障不阻断基本调查；结果仍需人工核验 | 企业模型质量、隐私、提示注入、引用正确性、成本/超时和人工处置边界 |
| [workbench](../frontend/apps/workbench) | Vue 3、TypeScript、Vue Query、Element Plus、ECharts、Vue Flow；Vite/Vitest/Playwright | 表格、调查和流程编辑适合组件化实现；查询缓存、路由和服务端权限必须一致 | 登录失效/CSRF、各角色真实操作、租户切换缓存、错误恢复、端到端调查与导出 |
| [采集资产](../agents) | Vector pipeline/runtime、Sysmon、Falco 配置 | 使用成熟采集器减少自研采集成本；交付可靠性依赖磁盘缓冲、确认和补发协议 | 固定二进制/补丁身份、503 重试、崩溃重放、真实 OS/设备兼容、磁盘耗尽 |

Detection 的生产运行时只有分区 lane 引擎。Kafka Streams 是测试对照，不能作为已部署能力描述，
见 [ADR 006](adr/006-detection-runtime-selection.md)。二次分析保留原 detect-model 数据库、
迁移历史和事务边界，但不是独立生产服务。

## 平台模块

| 模块 | 设计、工具和理由 | 必须保持的边界 |
|---|---|---|
| [socp-auth](../platform/socp-auth) | JWT/JWKS、角色/服务/采集身份、ProdGuard；集中复用验证逻辑 | prod 失败关闭；不能只信网关或客户端传来的 tenantId |
| [socp-tenant](../platform/socp-tenant) | 租户上下文传播 | 异步任务和线程复用不串租户；数据库 RLS 仍需启用 |
| [socp-data](../platform/socp-data) | 共享持久化字段及数据基础约定 | 共享模型不意味着跨领域直接写表 |
| [socp-audit](../platform/socp-audit) | 统一审计事件 | 安全关键操作可追溯，敏感信息不能泄露进日志 |
| [socp-ratelimit](../platform/socp-ratelimit) | Redis 分布式限流、本地开发回退 | 生产禁止本地状态代替分布式约束；容量不足必须显式失败 |
| [socp-obs](../platform/socp-obs) | 日志/trace/Micrometer 指标公共约定 | 指标基数有界，metrics 受保护，依赖失败与业务失败可区分 |
| [socp-error](../platform/socp-error) | 统一响应及错误语义 | 保持准确 4xx/5xx，不泄露堆栈和内部凭据 |
| [socp-rule](../platform/socp-rule) | Canonical SecurityEvent、规则族、窗口/抑制/UEBA 基元 | 正负样本、事件时间和状态恢复语义一致；不能凭规则数量宣称检测质量 |
| [socp-client](../platform/socp-client) | 类型化服务客户端、trace 传播和显式失败 | 失败不可伪装成功，重试须符合幂等和超时预算 |
| [socp-starter](../platform/socp-starter) | 显式 servlet 自动配置与 OpenAPI | 包扫描限制在声明边界，避免隐式加载其他业务域 |
| [socp-test](../platform/socp-test) | 共享测试支持 | 测试替身不能代替真实中间件、厂商或生产环境验收 |
| [socp-parent](../pom.xml) | Maven/BOM 统一依赖、Java 21 工具链 | 持续漏洞审计；Jackson 2 迁移兼容模块有后续迁移成本 |

## 存储、部署和运维选型

| 工具 | 适用性与代价 | 准入证据 |
|---|---|---|
| PostgreSQL + Flyway | 事务事实、租户 RLS、journal/outbox 适合关系数据库；增加表/迁移及锁竞争管理 | 受限运行账户、独立迁移角色、FORCE RLS、升级/恢复、PITR 与容量 |
| Kafka | 分区重放解耦采集、检测、索引；顺序只在分区内，提交必须连续 | replication/min ISR、积压留存、跨节点故障、消费重平衡 |
| OpenSearch | 原始事件全文与字段检索；动态字段和分片数量需要治理 | TLS、持久盘、多节点分片分配、mapping/留存、快照恢复 |
| ClickHouse | 告警分析与趋势查询；跨库存储最终一致 | 逻辑去重、回灌、保留与恢复、查询 SLO |
| Redis | 跨副本限流、防重放、会话撤销；此处承载正确性状态 | noeviction、认证、容量和故障策略；不能只按可丢失缓存运维 |
| Temporal | 长运行自动化、等待与重试 | 独立 HA/恢复及工作流版本兼容，外部副作用幂等 |
| S3 兼容存储 | 报表和执行产物归档 | 有支持的供应商或明确补丁责任、加密/保留/恢复；本地 MinIO fixture 不能代替生产支持承诺 |
| Helm / Kubernetes / Docker Compose | Helm 提供独立扩缩容、探针、HPA/PDB；Compose 适合本地集成 | prod 角色、digest 镜像、Pod 限权、真实跨故障域；HPA/PDB 配置存在不代表 HA 已验证 |
| Prometheus/Grafana 与运行手册 | 指标、看板、告警及处置入口 | 在目标环境实际触发告警、验证路由、处置和恢复；静态资源一致不代表监控已上线 |

多存储设计符合 OLTP、全文检索和分析各自负载，但必须承担跨系统对账与灾备一致性成本，
见 [ADR 002](adr/002-storage-responsibilities.md) 和 [ADR 003](adr/003-transactional-outbox.md)。
至少一次传输配合稳定身份、事务和幂等收敛，不应宣传为跨系统 exactly-once。

默认负载、留存、延迟与恢复目标见 [候选验收基线](acceptance-baseline.zh-CN.md)。
这些目标需要测量达标，不能当作现有能力声明。

## 生产验收判定

只有指定版本在指定部署环境的所有准入项完成，才能声明生产可用。
缺少下列任一类证据时保持 **BLOCKED / 未验收**，不要以 dev/pg、模拟 HTTP、单节点 chaos
或历史 CI 的成功代替：

1. 明确 EPS、峰值、事件大小、规则量、租户数、查询并发、留存、SLO、RPO/RTO，并持续压测。
2. PostgreSQL、Kafka、OpenSearch、ClickHouse、Redis、Temporal 和对象存储的持久化、复制、独立故障域及恢复实测。
3. 生产账户、RLS、TLS、密钥/证书轮换、企业 IdP、镜像 OS 与应用依赖扫描证据。
4. 实际设备/采集器、CMDB、TAXII、通知供应商和 SOAR 连接器的正向及异常验收；启用 LLM 时追加模型评估。
5. 原始事件→检测→告警→案件/响应→报表的完整可追溯证据，并保留重复、故障恢复与跨租户负例。
6. 发布版本一致的 CI、迁移、回滚、备份恢复、告警路由和运维交接记录。

逐项操作与既有脚本见 [生产基线](production-readiness.md)、[验证矩阵](validation-matrix.md)、
[容量规划](operations/capacity-planning.md)、[备份恢复](operations/backup-restore.md)
及 [组件验收](operations/component-acceptance.md)。
