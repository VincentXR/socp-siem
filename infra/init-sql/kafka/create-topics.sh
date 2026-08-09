#!/usr/bin/env bash
# 创建 SOCP 系统 Kafka topic（§5 全量重定）。共 18 个系统 topic（不含租户前缀）。
# 运行方式（在 kafka 容器内或能连通 :9092 的主机）：
#   docker exec -i socp-kafka bash < create-topics.sh
set -e
BOOTSTRAP=${BOOTSTRAP:-localhost:9092}
PARTITIONS=${PARTITIONS:-3}
REPLICATION=${REPLICATION:-1}

# 18 个系统 topic（租户级 topic 由 ProducerInterceptor 在运行时加 {tenantId}- 前缀）
TOPICS=(
  socp-audit
  socp-alarm-original
  socp-alarm-analyzed
  socp-event
  socp-rule-match
  socp-soar-command
  socp-soar-callback
  socp-hipscollect-register
  socp-hipscollect-heartbeat
  socp-hipscollect-alert
  socp-hips-web-event
  socp-report-aggregate
  socp-asset-intel
  socp-asset-cmdb
  socp-search-parse
  socp-gateway-access
  socp-ai-request
  socp-dlt
)

for t in "${TOPICS[@]}"; do
  kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
    --create --if-not-exists \
    --partitions "$PARTITIONS" --replication-factor "$REPLICATION" \
    --config "retention.ms=604800000" \
    --topic "$t" && echo "created: $t"
done

# 死信队列启用重试/DLT 策略（§8.3：acks=all + 幂等 + RetryTopic + DLT）
kafka-configs.sh --bootstrap-server "$BOOTSTRAP" --entity-type topics \
  --entity-name socp-dlt --alter --add-config "cleanup.policy=delete" || true

echo "Kafka 系统 topic 初始化完成（${#TOPICS[@]} 个）"
