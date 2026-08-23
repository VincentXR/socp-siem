#!/usr/bin/env bash
# 启动 SOCP 本地纵切切片：alert-web + search-config + detect-web + api-gateway
# （只想验证横切能力/主链路时用它，比 run-all.sh 快得多）
#
# 【为什么端口要用命令行参数显式传】
#   某些机器上存在 SERVER__PORT=0 这类环境变量，Spring Boot 宽松绑定会把它折叠成
#   server.port=0，导致服务起在随机端口，且 application.yml 里的配置被环境变量压过。
#   Spring Boot 属性优先级：命令行参数 > 环境变量 > application.yml，
#   所以这里必须用 --server.port=xxx 才能稳定生效。
#
# 用法： bash build/run-slice.sh start|stop|status
# 端口来源：build/ports.env（唯一来源），本文件不再自己维护端口。

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=toolchain.sh
source "$SCRIPT_DIR/toolchain.sh"
# shellcheck source=ports.env
source "$SCRIPT_DIR/ports.env"

ROOT="$SOCP_ROOT"
LOGDIR="$ROOT/.cache"
# The slice check only needs the alert persistence service and gateway.
# Kafka/OpenSearch-backed ingestion services belong to the pipeline workflow.
SLICE="alert-web api-gateway"

export SOCP_JWT_SECRET="${SOCP_JWT_SECRET:-socp-demo-jwt-secret-0123456789abcdef0123456789abcdef}"
export SOCP_LOGIN_SECRET="${SOCP_LOGIN_SECRET:-$SOCP_JWT_SECRET}"
export SOCP_SECURITY_SERVICE_SECRET="${SOCP_SECURITY_SERVICE_SECRET:-socp-demo-service-secret-change-me}"
export SOCP_AUDIT_SINK="${SOCP_AUDIT_SINK:-memory}"

mkdir -p "$LOGDIR"

jar_of() { printf '%s/services/%s/target/%s-1.0.0-SNAPSHOT.jar' "$ROOT" "$1" "$1"; }

pid_on_port() {
  if command -v netstat >/dev/null 2>&1 && netstat -ano >/dev/null 2>&1; then
    netstat -ano 2>/dev/null | grep -i listen | grep ":$1 " | awk '{print $NF}' | head -1
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$1" -s tcp:LISTEN 2>/dev/null | head -1
  fi
}

kill_pid() {
  [ -z "${1:-}" ] && return 0
  if command -v taskkill >/dev/null 2>&1; then
    # MSYS_NO_PATHCONV=1 必须加：否则 Git Bash 会把 /F /PID 当路径转换，taskkill 报无效参数
    MSYS_NO_PATHCONV=1 taskkill /F /PID "$1" >/dev/null 2>&1
  else
    kill -9 "$1" >/dev/null 2>&1
  fi
}

case "${1:-start}" in
  start)
    JAVA="$(socp_java)" || exit 1
    for name in $SLICE; do
      port="$(socp_port "$name")"
      jar="$(jar_of "$name")"
      [ -f "$jar" ] || { echo "跳过 $name（jar 不存在）"; continue; }
      ctx="$(socp_ctx "$name")"
      echo "启动 $name -> http://localhost:$port/${ctx}"
      # 网关的下游地址走 SOCP_*_URI 环境变量（ports.env 已 export），不要用命令行覆盖 routes[0].uri：
      # Spring Boot 绑定 List 时只取优先级最高的那个属性源，命令行里只写 uri 会导致
      # predicates 整个丢失，启动直接报 "Property: routes[0].predicates Value: []"。
      if [ "$name" = "api-gateway" ]; then
        "$JAVA" -jar "$jar" --server.port="$port" --spring.profiles.active=dev > "$LOGDIR/$name.log" 2>&1 &
      else
        "$JAVA" -jar "$jar" --server.port="$port" > "$LOGDIR/$name.log" 2>&1 &
      fi
    done
    echo "日志目录：$LOGDIR"
    ;;
  stop)
    for name in $SLICE; do
      port="$(socp_port "$name")"
      pid="$(pid_on_port "$port")"
      [ -n "$pid" ] && { echo "停止 $name :$port PID=$pid"; kill_pid "$pid"; }
    done
    ;;
  status)
    for name in $SLICE; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$(socp_health_url "$name")" 2>/dev/null || echo down)
      echo "$name :$(socp_port "$name") -> $code"
    done
    ;;
  *)
    echo "用法: $0 {start|stop|status}"; exit 1;;
esac
