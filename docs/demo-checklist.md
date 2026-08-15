# Demo Checklist

这份清单用于简历链接和面试演示。重点是证明一条真实的安全事件闭环，
而不是逐个展示服务数量。

## Golden Demo：3～5 分钟

故事固定为：SSH 暴力破解，随后同一来源成功登录。

```text
sshd raw log
  -> Vector
  -> search-config: parse + canonical normalize
  -> Kafka socp-events
  -> detect-web: threshold / correlation
  -> alert-web: PostgreSQL + Outbox
       -> incident-web / soar-web / notify-web
event -> OpenSearch investigation
alert -> ClickHouse analytics
```

准备并启动完整本地链路：

```bash
docker compose -f infra/docker-compose.yml up -d
bash build/run-all.sh backend
bash build/run-vector.sh start
python build/demos/golden-demo.py
```

脚本会向 `demo/sample.log` 追加 5 条唯一的 `Failed password`，等待
`AUTH-BRUTE`，再追加一条 `Accepted password`，等待
`AUTH-BRUTE-SUCCESS`，并验证事件、告警、Incident、SOAR、Notify 和报表。
其中通知使用本地 EMAIL 记录渠道，不会发出外部邮件。

若 Vector 未启动，或只需排查 search-config 之后的链路，可运行：

```bash
python build/demos/golden-demo.py --transport ingest
```

现场页面只展示五件事：canonical 字段、告警 ATT&CK（T1110/T1078）、
Incident 时间线、SOAR 执行结果、Audit/Trace。Kafka、PG、OpenSearch、
ClickHouse 的职责在面试追问时展开，不需要逐个打开管理页面。

## Failure Demo：Kafka 保证积压和恢复

1. 启动 Golden Demo 所需服务。
2. 停止 `detect-web`，继续注入事件，观察 `socp-events` consumer lag 上升。
3. 重启 `detect-web`，观察 lag 下降并继续产生告警。
4. 解释：采集与检测解耦，语义是 at-least-once；消费者使用手动提交、
   event-id 去重和 DLQ，不宣称跨系统 exactly-once。

脚本化依赖故障可使用 `python build/failure-tests.py`；它覆盖 Kafka、
OpenSearch、Temporal 和 PostgreSQL 的停止/恢复场景。

## 展示边界

- `build/demos/attack-scenarios.py` 是多规则 playground，不是主链路 Demo。
- 不展示真实凭据、客户数据、私有主机名或本地数据库文件。
- Jaeger 只有在启用 tracing/Jaeger 时展示；没有追踪后端时以响应中的 trace ID
  和日志关联作为替代证据。
