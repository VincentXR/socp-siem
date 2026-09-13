# Detection 运行角色

`detect-web` 现在支持同一构件的三种运行角色，由
`SOCP_DETECT_RUNTIME_ROLE` 控制：

| 角色 | 默认行为 | 主要职责 |
| --- | --- | --- |
| `all` | 是 | 本地开发兼容模式，同时启用管理 API 与 Detection worker |
| `api` | 否 | 规则管理、校验、Dry Run、观察名单、运行运维查询和 Outbox 管理 |
| `worker` | 否 | Kafka 分区消费、规则变更监听、状态恢复、Detection Alert Outbox 投递和二次分析 |

API 角色只写规则/事件/Outbox 数据，不拥有 Detection Kafka consumer、规则变更
consumer 或告警 Outbox 投递线程。Worker 角色不注册管理控制器；它只保留健康
检查、运行时端点和内部二次分析端点。`all` 仅用于本地单进程开发和兼容旧启动
脚本。

API 角色中的 `DetectEngineService` 不读取快照、不 replay journal，也不创建
live rule engine；其 `stats` 明确报告 `detectionWorkerEnabled=false`。规则管理
接口提交的变更由 `t_rule_change_outbox` 交给 Worker，Worker 才执行本地 reload、
分区恢复和实时计算。

## 生产编排

生产 Compose 用同一镜像启动两个独立容器：

```text
detect-web-api     SOCP_DETECT_RUNTIME_ROLE=api
detect-web-worker  SOCP_DETECT_RUNTIME_ROLE=worker
```

Gateway 和 `search-config-api` 的检测管理流量只访问 `detect-web-api`。兼容路由
`/detect-model/**` 则转发到 Worker 的 `/detect-web/model/**` 内部端点。Worker
使用相同的 Detection PostgreSQL、Kafka 和 Alert Web，但不接收管理流量，因此
API 重启不会重启 Kafka 分区 owner 或告警投递循环。Worker 可以独立扩容；同一
个 Kafka group 和数据库 owner fencing 仍保护分区状态。

规则管理写入 `t_rule_change_outbox`，由 Worker 发布规则变更；API 角色中的
publisher bean 仅保留写入能力，定时 drain 在 API 进程中 fail-closed 地停用。
Detection Alert Outbox 同样由 Worker 投递，API 进程可以写入结果但不会触发
远程告警调用。

原 `detect-model` 二次分析能力已并入 Worker 构件，但没有并库：它继续连接
`detect_model` 数据库，使用原有四个 Flyway 迁移和独立事务管理器，并保留
`socp-detect-model` Kafka consumer group。升级时无需搬迁分析结果或重置消费
位点；同一版本中不再启动独立的 `detect-model` 进程。

这项拆分是生命周期和部署边界，不改变事件语义：本地开发仍保留 `all`，真实
多实例 owner fencing、Kafka 恢复和容量演练仍需在可用 middleware 环境中验证。
