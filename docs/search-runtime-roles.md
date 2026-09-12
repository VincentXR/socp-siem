# SEARCH 运行角色

`search-config` 现在支持同一构件的三种运行角色，由
`SOCP_SEARCH_RUNTIME_ROLE` 控制：

| 角色 | 默认行为 | 主要职责 |
| --- | --- | --- |
| `all` | 是 | 本地开发兼容模式，同时启用 API 与持续处理 worker |
| `api` | 否 | 管理 API、接入标准化、查询和 Outbox 管理；只写入事件与发布意图 |
| `worker` | 否 | 扫描 ingestion Outbox 发布 Kafka，并消费 Kafka 写入 OpenSearch |

API 与 worker 通过 PostgreSQL/H2 中的事件表和 ingestion Outbox 交接。API
角色在事务提交后不依赖本进程内的 Kafka 发布线程；worker 角色会持续扫描
`PENDING` Outbox，因此 API 进程重启不会丢失已确认的接入事件。

## 部署约定

开发环境保持默认值：

```text
SOCP_SEARCH_RUNTIME_ROLE=all
```

生产环境可以启动两个相同版本的构件：

```text
search-config-api     SOCP_SEARCH_RUNTIME_ROLE=api
search-config-worker  SOCP_SEARCH_RUNTIME_ROLE=worker
```

`infra/docker-compose.prod.yml` 已按这两个服务名编排：gateway 只指向
`search-config-api`，worker 不暴露给 gateway，也不承载管理请求。两者可以
独立滚动重启和扩缩容；worker 的 Kafka consumer group/Outbox lease 仍负责
保护持续处理路径。

Detection 使用同样的生命周期边界，详见
[`docs/detection-runtime-roles.md`](detection-runtime-roles.md)。

两个角色必须使用同一个事务数据库和相同的 Kafka/OpenSearch 契约。worker
仍然应通过健康检查和独立的消费指标进行扩缩容；API 的接入成功只表示事件
及其 Outbox 已提交，不表示 Kafka 已经完成传输。

该角色开关是逻辑/部署边界，不是数据所有权放宽。事件和 Outbox 的唯一约束、
发布重试、Kafka offset 以及 OpenSearch 文档幂等仍由各自 owner 负责。
