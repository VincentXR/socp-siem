# suggest1 整改工作记忆

本台账按 `.cache/suggest1.txt` 的优先级维护。`已完成` 只表示代码与对应的本地测试已闭环，不代表生产环境验收；`进行中` 表示本轮已实现部分能力，但仍有边界或部署证据缺口。

更新时间：2026-09-13（Asia/Shanghai）

| 建议项 | 状态 | 本轮边界 / 证据 |
| --- | --- | --- |
| 1. 重构范围与服务边界 | 进行中 | 先集中整改 Detection runtime；生产编排已将 `search-config` 拆为 `search-config-api`/`search-config-worker`，并将 `detect-web` 拆为 `detect-web-api`/`detect-web-worker`；仓库默认拓扑仍保留兼容性进程，完整 15 单元收敛与真实部署验收仍待后续。 |
| 2. 管理请求与持续事件处理分离 | 进行中 | `SOCP_SEARCH_RUNTIME_ROLE` 与 `SOCP_DETECT_RUNTIME_ROLE` 均提供 `all/api/worker` 角色；API 负责管理/提交事件与 Outbox，Worker 承接 Kafka、规则变更、OpenSearch/Alert Outbox 持续循环；角色条件测试、生产 Compose/K8s 编排及静态角色门禁已落地。 |
| 3. 分区、状态、检查点和结果统一 | 进行中 | 状态单元绑定 input topic/partition/shard 与 routing version；快照 key、精确 offset replay、分配内重建和旧 epoch completion 已修正；新增 durable DB owner lease/fencing（V16/V17）、revoke 即失效、事务内 token 校验及 checkpoint owner-epoch vector、快照 topic 约束（V18）；真实多实例故障演练仍待执行。 |
| 4. 规则计算与结果提交分离 | 进行中 | `DetectionResult` 已显式携带输入位置、规则版本、状态变更摘要、候选/已发告警、抑制决定和幂等键；Alert Outbox 与 COMPLETED 在同一 owner-guarded 事务提交，事件 journal 以 `result_json`（V19）留存无原始载荷的结果摘要，Alert 侧以 `detection_result_json`（V19）贯穿投递、详情和调查；源事件租户对告警 evidence 具权威性，混租户 evidence 在任何持久化前拒绝；真实依赖故障/重试证据仍待执行。 |
| 5. 聚合维度、乱序、规则版本 | 进行中 | Stateful DSL 已显式化 `groupBy`/`routingField` 一致性；阈值/有序关联/无序关联使用单调 event-time watermark 与 `lateEventPolicy`（默认窗口范围后 DROP），快照保存 watermark；跨实体重分区尚未实现，当前对不一致配置显式返回 HTTP 400，避免静默降级语义。 |
| 6. 接入与存储协议 | 进行中 | 暂不迁移 Kafka-first；已补采集位置/Idempotency-Key 身份优先级、canonical 内容指纹、租户内事件+Outbox 唯一约束、重试去重和同身份异内容 409；canonical identity 忽略 JSON 字段顺序及 `ingested_at` 等运行时元数据，缺失事件时间写入显式 generated marker，损坏 Outbox payload 按冲突 fail-closed；批次 200 条事务边界、503 重试约定及归一化后的字段预算已落地；Detection 现将 `ecs` 规范字段桥接到规则字段视图，Kafka-first 迁移及真实负载验证待后续。 |
| 7. 背压与多租户隔离 | 进行中 | 已有分区串行 lane/队列上限；新增分区级 in-flight/queued/deferred payload 字节预算与局部 pause/resume；新增按租户独立的速率、pending bytes、活动 routing entity 配额及规则级熔断；真实压测与跨实例限流仍待后续。 |
| 8. 告警、案件、SOAR 边界 | 已有基础 | Detection Alert Outbox 已存在；本轮不重复改 SOAR，处置边界另行验收。 |
| 9. 查询与前端完整性 | 进行中 | 已有 SPL 查询、OpenSearch/本地降级标识、字段浏览、时间范围、游标分页、限量导出、保存查询和事件详情；新增 `/alert-web/api/alarms/by-event`，事件详情可反查直接触发/证据关联告警并展示规则版本、检测位置和跳转入口。更完整的跨系统浏览器验收仍待后续。 |
| 10. 分阶段退出条件 | 进行中 | 阶段 A 已完成；阶段 B 已增加隔离的 Kafka Streams 3.9.2 对照，并已在 `docs/adr/006-detection-runtime-selection.md` 记录现有 partition-lane runtime 为唯一生产实现；broker-backed changelog 恢复/故障演练仍待执行，阶段 C-F 尚未完成。最新仓库质量门禁已通过（27 个 Maven 模块、聚合覆盖率 81.97%、变更行覆盖率 81.76%；前端 50 项测试通过），真实环境退出证据仍待补齐。 |

## 阶段 A 验收清单

- [x] 分区快照包含 topic、partition、routing version；旧 assignment epoch 的 completion 不会推进新 owner 的提交。
- [x] 规则配置/路由语义变化不会复用不兼容的 state snapshot。
- [x] durable sink 完成后统一标记事件 `COMPLETED`；重复投递只产生一次业务效果。
- [x] durable sink 失败时，抑制决定和有状态规则窗口不会留下“已成功”的内存状态。
- [x] 阈值规则对乱序事件不依赖队列头部的到达顺序。
- [x] 接入事件优先使用生产者/采集位置身份；重试不重复创建事件和 Outbox，同身份异内容明确拒绝。
- [x] 完整 Detection 测试、迁移检查、前端 i18n/格式检查和 `git diff --check` 已通过；真实多实例/故障恢复证据仍单独记录。
- [x] 仓库级 Maven 全量通过；已修复 `search-config/MetaController` 的重复映射。

## 阶段 B 对照记录

- [x] Kafka Streams 对照依赖仅在 `socp-rule` 测试范围启用，生产 Detection 仍使用现有 consumer。
- [x] 阈值规则乱序输入与当前 `ThresholdRule` 的告警数量/窗口结果对照通过。
- [x] 通过 KTable 配置流验证无需重建 topology 的动态阈值切换。
- [x] 验证 Streams state store 的状态物化可查询；TopologyTestDriver 的关闭行为会删除本地 task state，不能替代 broker changelog 恢复证明。
- [ ] 使用真实 Kafka broker 验证 changelog 恢复、重分配和 fencing/外部 PostgreSQL 提交边界。
- [x] 基于对照结果选定现有 partition-lane runtime 为唯一生产运行时并形成 ADR；本轮无需生产运行时迁移，Kafka Streams 仅保留测试对照。

## 本轮验证

- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl services/detect-web -am test -Dsurefire.failIfNoSpecifiedTests=false`：12 个模块成功；`detect-web` 146 项通过、3 项环境相关跳过。
- `python build/verify-migrations.py`：13 个模块、96 条迁移通过（含 V18/V19）。
- `git diff --check`：通过。
- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl platform/socp-rule test -Dtest=KafkaStreamsDetectionComparisonTest -Dsurefire.failIfNoSpecifiedTests=false`：2 项通过；仅覆盖隔离 topology 对照，不代表 broker 故障恢复。
- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl services/detect-web -am test -Dtest=KafkaEventConsumerTest -Dsurefire.failIfNoSpecifiedTests=false`：7 项通过，包含字节预算触发的分区暂停/恢复。
- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl services/search-config -am test "-Dsurefire.failIfNoSpecifiedTests=false"`：12 个模块成功；172 项测试、12 项环境条件跳过（类级别角色测试另由仓库全量纳入）。
- `mvn -B -s build/settings-mirror.xml -f pom.xml test -Dsurefire.failIfNoSpecifiedTests=false`：27 个模块成功，仓库级全量通过。
- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl services/search-config -am test "-Dtest=IngestionCommitServiceTest,SearchRuntimeRoleConditionTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：8 项通过；覆盖发布端口解耦后的接入提交及 API/Worker 基础角色条件。
- `mvn -B -s build/settings-mirror.xml -f pom.xml -pl services/search-config -am test "-Dtest=SearchRuntimeRoleConditionTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：5 项通过；补充类级别角色条件与非法配置 fail-closed 验证。
- `docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config --quiet`（使用临时非生产占位变量）：Compose 解析通过；Docker daemon 当前不可用，未执行容器启动。
- `mvn -B -s build/settings-mirror.xml -f pom.xml test "-Dsurefire.failIfNoSpecifiedTests=false"`：27 个模块成功；包含本轮 search-config 角色拆分与发布端口改动。
- 最后一轮 `build/quality-gate.ps1` 内 Maven coverage 全量回归：27 个模块成功；最终 309 份新鲜 Surefire 报告共 1,646 项测试，失败 0、错误 0、跳过 24；包含 ECS 字段桥接、API 角色不恢复状态、租户边界校验和 canonical ingestion identity 后的最终回归。
- Windows 等价验证：`build/mvnw.ps1 -pl services/detect-web -am test "-Dtest=RuleEngineTest,AlertForwarderTest,DetectionEventJournalTest,JpaDetectionStateSnapshotStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：结果对象、结果摘要、快照 topic 绑定相关测试通过。
- Windows 等价验证：`build/mvnw.ps1 -pl platform/socp-rule -am test "-Dtest=RuleEngineTest,StatefulRuleBehaviorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：24 项通过；覆盖 late watermark、快照回滚及坏规则熔断。
- Windows 等价验证：`build/mvnw.ps1 -pl services/detect-web -am test "-Dtest=TenantAdmissionTest,DetectEngineServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：21 项通过；覆盖按租户配额隔离与检测服务链路。
- Windows 等价验证：`build/mvnw.ps1 -pl services/alert-web -am test "-Dsurefire.failIfNoSpecifiedTests=false"`：12 个模块成功；Alert Web 121 项测试通过、1 项环境条件跳过，覆盖事件血缘查询与 DetectionResult 摘要投递。
- `pnpm test`（`frontend/apps/workbench`）：Node 19 项、Vitest 50 项全部通过。
- `pnpm run build` + `node scripts/verify-build.mjs`（`frontend/apps/workbench`）：Vite 生产构建和 55 资源 smoke check 通过；`pnpm verify` 首次执行先触发依赖安装并因 `ERR_PNPM_IGNORED_BUILDS` 退出，依赖安装完成后重试通过。
- `python build/runtime-topology.py --check` 与 `python build/verify-contracts.py`：拓扑、15 个默认进程、6 个目标单元及网关路由契约检查通过。
- `python build/verify-production.py`：K8s 双 Detection Deployment、摘要资源/探针/安全基线、HPA 目标及生产 Compose API/Worker 角色门禁通过。
- `build/quality-gate.ps1`：角色拆分、租户边界、幂等 identity、前端 i18n 和 ADR 收口后的最终质量门禁通过；27 个模块，1,646 项测试，失败 0、错误 0、跳过 24。
- `docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml config --quiet`（临时占位变量）：生产 Compose API/Worker 拆分解析通过；Docker daemon 不可用，未启动容器。
- `build/mvnw.ps1 -pl services/detect-web -am test "-Dtest=DetectionRecordProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：5 项通过；覆盖 ECS 规范字段桥接与非法 ECS 信封拒绝。
- `build/mvnw.ps1 -pl services/detect-web -am test "-Dtest=AlertForwarderTest,DetectEngineServiceTest,DetectionRecordProcessorTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：31 项通过；覆盖混租户 evidence 拒绝、API 角色不恢复/不创建 live engine、ECS 字段桥接和非法 ECS 信封拒绝。
- `build/mvnw.ps1 -pl services/search-config -am test "-Dtest=IngestionCommitServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`：6 项通过；覆盖字段顺序变化的 canonical retry、生成事件时间的 identity 稳定性及损坏 Outbox 的 fail-closed 冲突处理。
- `python build/verify-frontend-i18n.py`：109 个源码文件、双语键 1,547 个通过；Workbench `test` 50 项、`lint`、`format:check`、生产 `verify` 及 55 个资源 smoke check 通过。
- `build/quality-gate.ps1`：最终完整门禁通过；包括 Maven coverage、迁移/合同/包布局/架构/样式/i18n/事件 schema/生产部署/检测内容/调查数据门禁，以及 Maven `verify -Pquality -DskipTests`。
- `docs/adr/006-detection-runtime-selection.md`：记录现有 partition-lane Detection runtime 为唯一生产实现，Kafka Streams 仅为测试对照；broker-backed changelog 测试仍需 `SOCP_TESTCONTAINERS=true` 和可用 Docker。
- Kubernetes search runtime follow-up (2026-09-12): completed the API/Worker split in the base and dev/staging/prod overlays. `search-config-api` is the only gateway route; `search-config-worker` owns continuous Outbox/Kafka/OpenSearch work. Production verifier and HPA checks now enforce both roles.
- Storage integrity follow-up (2026-09-12): migrated `t_search_event.msg`, `fields_json` and `ecs_json` from the old 2/4 KiB VARCHAR columns to TEXT, added an H2 migration proof with 5,000-character values, and reject source/host/event IDs over the remaining indexed 255-character contract before persistence.
- Runtime boundary follow-up (2026-09-12): `SearchStore`, `IngestPipeline` and `IngestionCommitService` are API-only; `OsEventWriter` is Worker-only and legacy direct ingest methods no longer write OpenSearch outside the Kafka/Outbox path. Targeted search role/storage regression: 32 tests passed.
- Cross-entity grouping follow-up (2026-09-12): stateful `groupBy`/`keyField`/`routingField` mismatches now return an explicit cross-entity contract error; rule persistence maps it to HTTP 400, and detection state-sharding/rule docs state the repartition/fan-out non-guarantee.
- Ingest boundary follow-up (2026-09-13): introduced typed `IngestParseException`; malformed or over-budget lines remain per-line parse rejects, while source/rule/reference configuration failures fail closed as HTTP 503. Received-byte accounting now happens before normalization, and commit/persistence paths reject a declared tenant different from the authenticated tenant. Targeted regression: 21 tests passed.
- Latest quality-gate result (2026-09-13): `build/quality-gate.ps1` passed end to end; 27 Maven modules succeeded, aggregate coverage was 81.97%, changed-line coverage 81.76%, and the frontend passed 50 Vitest tests, lint, format, production build, and 55-asset smoke verification. Detection content, investigation dataset/eval, event schema, production deployment, migration, topology, architecture, package-layout, and i18n gates all passed.
- Evidence boundary (2026-09-13): Docker Desktop's Linux engine is unavailable in this workspace, so the Compose configuration was parsed but broker-backed multi-instance/recovery/load/backup/upgrade evidence remains pending. The production runtime contract currently keeps the custom partition-lane detector as the sole production implementation; Kafka Streams remains an isolated comparison test until broker-backed evidence is available.
