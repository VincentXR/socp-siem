#!/usr/bin/env bash
# 创建 SOCP 代码实际使用的 Kafka topic：主链 topic + 各消费者硬编码派生的 <topic>-dlq 死信队列。
# 名单以代码为准（生产者/消费者的 @Value 默认值与 *Properties 字段）。新增主链 topic 或
# DLQ 派生命名时必须同步这里：broker 关闭 auto-create 后，缺失 topic 先打断生产端，
# 再让终态 hand-off 永久失败（detect 侧 handoffToDlqUntilDurable 放弃、alert 侧无限回卷）。
# 名称按默认配置派生；若用 SOCP_KAFKA_* 覆盖了 topic，需相应改名。
# 运行方式（在 kafka 容器内或能连通 :9092 的主机）：
#   docker exec -i socp-kafka bash < create-topics.sh
set -e
BOOTSTRAP=${BOOTSTRAP:-localhost:9092}
PARTITIONS=${PARTITIONS:-3}
REPLICATION=${REPLICATION:-1}
# 主链保留 7 天；死信队列是终态取证证据，保留 30 天。
RETENTION_MS=${RETENTION_MS:-604800000}
DLQ_RETENTION_MS=${DLQ_RETENTION_MS:-2592000000}

TOPICS=(
  socp-events          # search-config 生产 → detect-web KafkaEventConsumer / search-config OsIndexerConsumer 消费
  socp-alarm-events    # alert-web AlertKafkaPublisher 生产 → AlarmEventConsumer 消费
  socp-alarm-original  # detect-web AlarmKafkaProducer 生产 → AlarmConsumer 消费
  socp-rule-changes    # detect-web RuleChangePublisher 生产 → RuleChangeListener 消费
  socp-audit           # 各服务 AuditSink 生产 → soc-base AuditConsumer 消费
)

# 每个 DLQ 都来自代码里的 `topic + "-dlq"`，不是自动创建以外的显式契约。
DLQ_TOPICS=(
  socp-events-dlq          # KafkaEventConsumer、OsIndexerConsumer
  socp-alarm-events-dlq    # AlarmEventConsumer
  socp-alarm-original-dlq  # AlarmConsumer
  socp-rule-changes-dlq    # RuleChangeListener
  socp-audit-dlq           # AuditConsumer
)

ensure_topic() {
  local topic=$1
  local retention=$2
  kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
    --config "retention.ms=$retention" \
    --config "cleanup.policy=delete" \
    --topic "$topic" && echo "ensured: $topic"
}

for t in "${TOPICS[@]}"; do
  ensure_topic "$t" "$RETENTION_MS"
done

for t in "${DLQ_TOPICS[@]}"; do
  ensure_topic "$t" "$DLQ_RETENTION_MS"
done

echo "Kafka topic 初始化完成（主链 ${#TOPICS[@]} 个 + 死信 ${#DLQ_TOPICS[@]} 个）"
