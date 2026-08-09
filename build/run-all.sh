#!/usr/bin/env bash
# SOCP 全栈启停脚本：17 个后端服务 + 1 个前端 dev server（workbench）
#
# 用法：
#   bash socp/build/run-all.sh start     # 启动全部后端 + 前端
#   bash socp/build/run-all.sh stop      # 停止全部
#   bash socp/build/run-all.sh status    # 探活
#   bash socp/build/run-all.sh backend   # 只起后端
#   bash socp/build/run-all.sh frontend  # 只起前端
#
# 端口约定（避开 8080 旧 SIEM 控制台）：
#   后端：18080~18097（17 个服务，见下方 BACKENDS 数组）
#   前端 dev：5173（仅 workbench 一个 app；早期的 alert/search/soar/report 已折叠进 workbench）

set -uo pipefail
# 仓库根：从脚本位置动态定位（socp/build/run-all.sh -> 仓库根），兼容分发/换目录
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# MSYS 下 pwd 输出 /d/... 风格，java 打不开带空格的此类路径 -> 转成 D:/... Windows 风格
case "$ROOT" in
  /[a-zA-Z]/*)
    if command -v cygpath >/dev/null 2>&1; then
      ROOT="$(cygpath -m "$ROOT")"
    else
      ROOT="$(printf '%s' "${ROOT:1:1}" | tr '[:lower:]' '[:upper:]'):${ROOT:2}"
    fi
    ;;
esac
# JDK：优先 JAVA_HOME，其次仓库自带 tooling，最后 PATH
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif [ -x "$ROOT/tooling/jdk-21.0.12+8/bin/java" ]; then
  JAVA="$ROOT/tooling/jdk-21.0.12+8/bin/java"
else
  JAVA="java"
fi
NODE="C:/Users/Ran/.workbuddy/binaries/node/versions/22.22.2/corepack.cmd"
LOGDIR="$ROOT/.cache"
FRONTEND="$ROOT/socp/frontend"
# JVM 内存：17 个内存态服务统一限堆，避免默认堆过大吃满机器内存（可用环境变量覆盖）
JVM_OPTS="${SOCP_JVM_OPTS:--Xms64m -Xmx384m}"

mkdir -p "$LOGDIR"

# 后端服务定义：name:port:context-path
BACKENDS=(
  "alert-web:18080:alert-web"
  "search-config:18081:search-config"
  "detect-web:18082:detect-web"
  "soar-web:18083:soar-web"
  "report-web:18084:report-web"
  "asset-web:18085:asset-web"
  "soc-base:18086:soc-base"
  "hips-web:18087:hips-web"
  "ai-assistant:18088:ai-assistant"
  "detect-model:18090:detect-model"
  "asset-collect:18091:asset-collect"
  "hips-collect:18093:hips-collect"
  "threat-web:18094:threat-web"
  "attack-web:18095:attack-web"
  "notify-web:18096:notify-web"
  "incident-web:18097:incident-web"
  "api-gateway:18092:"
)

# 前端：统一控制台（单端口 5173，侧边栏切模块，不再分散多端口）
FRONTENDS=(
  "workbench:5173"
)

start_backend() {
  for spec in "${BACKENDS[@]}"; do
    IFS=':' read -r name port ctx <<< "$spec"
    jar="$ROOT/socp/services/$name/target/$name-1.0.0-SNAPSHOT.jar"
    if [ ! -f "$jar" ]; then
      echo "  [跳过] $name (jar 不存在，请先构建)"
      continue
    fi
    # 先杀掉占端口的旧进程
    pid=$(netstat -ano -p tcp 2>/dev/null | grep -i listen | grep ":$port " | awk '{print $NF}' | head -1)
    if [ -n "${pid:-}" ]; then
      MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" > /dev/null 2>&1
    fi
    "$JAVA" $JVM_OPTS -jar "$jar" --server.port=$port > "$LOGDIR/$name.log" 2>&1 &
    echo "  [启动] $name -> :$port  (日志 .cache/$name.log)"
  done
}

start_frontend() {
  for spec in "${FRONTENDS[@]}"; do
    IFS=':' read -r name port <<< "$spec"
    pid=$(netstat -ano -p tcp 2>/dev/null | grep -i listen | grep ":$port " | awk '{print $NF}' | head -1)
    if [ -n "${pid:-}" ]; then
      echo "  [已在运行] $name :$port"
      continue
    fi
    cd "$FRONTEND"
    "$NODE" pnpm@10.0.0 --filter "@socp/app-$name" dev > "$LOGDIR/frontend-$name.log" 2>&1 &
    cd "$ROOT"
    echo "  [启动] frontend/$name -> :$port  (日志 .cache/frontend-$name.log)"
  done
}

stop_port() {
  local port=$1
  pid=$(netstat -ano -p tcp 2>/dev/null | grep -i listen | grep ":$port " | awk '{print $NF}' | head -1)
  if [ -n "${pid:-}" ]; then
    MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" > /dev/null 2>&1
    echo "  [停止] :$port PID=$pid"
  fi
}

stop_all() {
  echo "=== 停止后端 ==="
  for spec in "${BACKENDS[@]}"; do
    IFS=':' read -r name port ctx <<< "$spec"
    stop_port "$port"
  done
  echo "=== 停止前端 ==="
  for spec in "${FRONTENDS[@]}"; do
    IFS=':' read -r name port <<< "$spec"
    stop_port "$port"
  done
}

status_all() {
  echo "=== 后端服务 ==="
  for spec in "${BACKENDS[@]}"; do
    IFS=':' read -r name port ctx <<< "$spec"
    path="/actuator/health"
    [ -n "$ctx" ] && path="/$ctx/actuator/health"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$port$path" 2>/dev/null)
    if [ "$code" = "200" ]; then
      echo "  ✅ $name  :$port  -> UP"
    else
      echo "  ❌ $name  :$port  -> $code"
    fi
  done
  echo "=== 前端 ==="
  for spec in "${FRONTENDS[@]}"; do
    IFS=':' read -r name port <<< "$spec"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$port/" 2>/dev/null)
    if [ "$code" = "200" ]; then
      echo "  ✅ $name  :$port  -> OK"
    else
      echo "  ❌ $name  :$port  -> $code"
    fi
  done
}

case "${1:-start}" in
  start)
    echo "=== 启动后端 17 服务 ==="
    start_backend
    echo "=== 启动前端 1 app (workbench) ==="
    start_frontend
    echo "=== 等待启动（8s）==="
    sleep 8 2>/dev/null || echo "(sleep 不可用，手动等待几秒)"
    echo "=== 状态 ==="
    status_all
    ;;
  backend)
    echo "=== 启动后端 ==="
    start_backend
    sleep 8 2>/dev/null || true
    status_all
    ;;
  frontend)
    echo "=== 启动前端 ==="
    start_frontend
    sleep 3 2>/dev/null || true
    status_all
    ;;
  stop)
    stop_all
    ;;
  status)
    status_all
    ;;
  *)
    echo "用法: $0 {start|stop|status|backend|frontend}"
    exit 1
    ;;
esac
