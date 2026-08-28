#!/usr/bin/env bash
# SOCP 本地启停脚本：按 profile 启动后端服务 + 前端 dev server（workbench）
#
# 用法（在仓库任意位置执行都可以，路径全部从脚本自身推导）：
#   bash build/run-all.sh doctor    # 环境自检：java / maven / pnpm / jar / 端口
#   bash build/run-all.sh start     # 默认启动完整后端 + 前端
#   bash build/run-all.sh start ui  # 启动全部业务页面依赖（不含采集器）+ 前端
#   bash build/run-all.sh start core # 只启动核心事件链 + 前端（低资源）
#   bash build/run-all.sh stop      # 停止全部
#   bash build/run-all.sh status    # 探活完整后端
#   bash build/run-all.sh status full
#   bash build/run-all.sh backend   # 只起完整后端（兼容 CI）
#   bash build/run-all.sh backend core|ui|full
#   bash build/run-all.sh frontend  # 只起前端
#
# 端口不在本文件里维护 —— 唯一来源是 build/ports.env，改端口只改那一份。
#   例：SOCP_PORT_ALERT_WEB=28080 bash build/run-all.sh backend

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=toolchain.sh
source "$SCRIPT_DIR/toolchain.sh"   # -> SOCP_ROOT / socp_java / socp_pnpm
# shellcheck source=ports.env
source "$SCRIPT_DIR/ports.env"      # -> SOCP_SERVICE_NAMES / SOCP_MODULE_NAMES / ports and service URLs

ROOT="$SOCP_ROOT"
LOGDIR="$ROOT/.cache"
FRONTEND="$ROOT/frontend"
FRONTEND_APP="workbench"
FRONTEND_PORT="$SOCP_PORT_FRONTEND_WORKBENCH"

# JVM 内存：开发默认使用较小堆；完整启动时可用 SOCP_JVM_OPTS 覆盖。
# 15 个默认进程同时启动时，降低 Xms/Xmx 能明显减少内存峰值和 GC 竞争。
JVM_OPTS="${SOCP_JVM_OPTS:--Xms32m -Xmx256m}"
START_BATCH_SIZE="${SOCP_START_BATCH_SIZE:-3}"
START_HEALTH_TIMEOUT="${SOCP_START_HEALTH_TIMEOUT:-45}"
# A full local deployment starts more PostgreSQL-backed services than the
# Hikari default (10 connections per process) can safely fit under the stock
# PostgreSQL max_connections setting. Keep the launcher budget bounded while
# allowing operators to override both values for larger environments.
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:-4}"
export SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE="${SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE:-1}"
# Named runtime modes: local (H2/file-backed where supported), integration
# (PostgreSQL overlays), or prod (fail-fast production guard).
RUNTIME_PROFILES="${SOCP_RUNTIME_PROFILES:-dev,pg}"

# core：Golden Demo 和日常后端开发所需的最小事件闭环。
CORE_SERVICES="alert-web search-config detect-web incident-web soar-web notify-web report-web api-gateway"

# ui：前端所有业务页面的服务依赖，不包含 asset/hips 采集器。
UI_SERVICES="alert-web search-config detect-web detect-model soar-web report-web asset-web soc-base hips-web ai-assistant threat-web attack-web notify-web incident-web api-gateway"

# 开发密钥：本机启动注入（生产必须通过环境变量显式提供，禁止使用此默认值）
# 签发与验签必须同值：login-secret 默认 = jwt-secret（否则 /auth/login 签发的 token 业务服务验签失败）
export SOCP_JWT_SECRET="${SOCP_JWT_SECRET:-socp-demo-jwt-secret-0123456789abcdef0123456789abcdef}"
export SOCP_LOGIN_SECRET="${SOCP_LOGIN_SECRET:-$SOCP_JWT_SECRET}"
export SOCP_SECURITY_SERVICE_SECRET="${SOCP_SECURITY_SERVICE_SECRET:-socp-demo-service-secret-change-me}"
export SOCP_SECURITY_METRICS_TOKEN="${SOCP_SECURITY_METRICS_TOKEN:-socp-demo-metrics-token}"
export SOCP_AUDIT_SINK="${SOCP_AUDIT_SINK:-kafka}"
export SOCP_AUDIT_FAIL_CLOSED="${SOCP_AUDIT_FAIL_CLOSED:-true}"
export SOCP_RATELIMIT_BACKEND="${SOCP_RATELIMIT_BACKEND:-memory}"

mkdir -p "$LOGDIR"

jar_of() { printf '%s/services/%s/target/%s-1.0.0-SNAPSHOT.jar' "$ROOT" "$1" "$1"; }

service_names() {
  case "${1:-core}" in
    core) printf '%s\n' "$CORE_SERVICES" ;;
    ui)   printf '%s\n' "$UI_SERVICES" ;;
    full) printf '%s\n' "$SOCP_SERVICE_NAMES" ;;
    *)
      echo "未知启动 profile: $1（可选 core|ui|full）" >&2
      return 1
      ;;
  esac
}

pid_on_port() {
  # Windows(netstat) 与 Unix(lsof) 两条路，找不到就返回空
  if command -v netstat >/dev/null 2>&1 && netstat -ano >/dev/null 2>&1; then
    netstat -ano 2>/dev/null | grep -i listen | grep ":$1 " | awk '{print $NF}' | head -1
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$1" -s tcp:LISTEN 2>/dev/null | head -1
  fi
}

kill_pid() {
  [ -z "${1:-}" ] && return 0
  if command -v taskkill >/dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 taskkill /F /PID "$1" >/dev/null 2>&1
  else
    kill -9 "$1" >/dev/null 2>&1
  fi
}

# ---------------------------------------------------------------------------
# doctor：冷启动前的环境自检（缺什么一次说清，别让人一个个撞）
# ---------------------------------------------------------------------------
doctor() {
  local fatal=0
  echo "=== 仓库根 ==="
  echo "  $ROOT"
  [ -f "$ROOT/pom.xml" ] || { echo "  ❌ 这里没有 pom.xml，脚本可能被移出仓库了"; fatal=1; }

  echo "=== 工具链 ==="
  local j; if j="$(socp_java)"; then
    echo "  ✅ java  : $j  ($("$j" -version 2>&1 | head -1))"
  else fatal=1; fi
  local m; if m="$(socp_maven)"; then
    echo "  ✅ maven : ${m#*|}  (来源 ${m%%|*})"
  else echo "  ⚠️  maven 缺失（只运行已构建的 jar 可以忽略）"; fi
  local p; if p="$(socp_pnpm)"; then
    echo "  ✅ pnpm  : $p"
  else echo "  ⚠️  pnpm 缺失（只跑后端可以忽略）"; fi

  echo "=== 构建产物 ==="
  local missing=0 name
  for name in $SOCP_MODULE_NAMES; do
    [ -f "$(jar_of "$name")" ] || { missing=$((missing + 1)); echo "  ❌ 缺 jar: $name"; }
  done
  if [ "$missing" -eq 0 ]; then
    echo "  ✅ 17/17 模块 jar 就绪（默认部署 15 个进程）"
  else
    echo "  → 执行 bash build/mvnw.sh -DskipTests package 生成"
  fi

  echo "=== 端口占用 ==="
  local busy=0 port pid
  for name in $SOCP_SERVICE_NAMES; do
    port="$(socp_port "$name")"
    pid="$(pid_on_port "$port")"
    if [ -n "$pid" ]; then
      busy=$((busy + 1))
      echo "  ⚠️  :$port 已被 PID=$pid 占用（$name）→ 可用 SOCP_PORT_$(printf '%s' "$name" | tr 'a-z-' 'A-Z_')=<新端口> 改开"
    fi
  done
  [ "$busy" -eq 0 ] && echo "  ✅ 15 个默认部署端口均空闲"

  echo
  [ "$fatal" -eq 0 ] && echo "自检通过，可以 bash build/run-all.sh start" || echo "存在致命问题，请先处理上面的 ❌"
  return "$fatal"
}

service_ready() {
  local name="$1" code
  code="$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$(socp_health_url "$name")" 2>/dev/null || true)"
  [ "$code" = "200" ]
}

wait_for_batch() {
  local batch="$1" elapsed=0 name all_ready
  while [ "$elapsed" -lt "$START_HEALTH_TIMEOUT" ]; do
    all_ready=1
    for name in $batch; do
      if ! service_ready "$name"; then
        all_ready=0
        break
      fi
    done
    [ "$all_ready" -eq 1 ] && return 0
    nap 1
    elapsed=$((elapsed + 1))
  done
  for name in $batch; do
    if service_ready "$name"; then
      echo "  [就绪] $name"
    else
      echo "  [等待超时] $name（继续启动下一批，详见 .cache/$name.log）"
    fi
  done
  return 0
}

start_service() {
  local java="$1" name="$2" port jar existing
  port="$(socp_port "$name")"
  jar="$(jar_of "$name")"
  if [ ! -f "$jar" ]; then
    echo "  [跳过] $name（jar 不存在，先跑 bash build/mvnw.sh -DskipTests package）"
    return 0
  fi
  existing="$(pid_on_port "$port")"
  if [ -n "$existing" ]; then
    echo "  [已运行] $name :$port PID=$existing"
    return 0
  fi
  nohup "$java" $JVM_OPTS -jar "$jar" --server.port="$port" \
    --spring.profiles.active="$RUNTIME_PROFILES" > "$LOGDIR/$name.log" 2>&1 < /dev/null &
  echo "  [启动] $name -> :$port  (日志 .cache/$name.log)"
}

start_backend() {
  local profile="${1:-full}" java name batch="" count=0
  java="$(socp_java)" || return 1
  local services
  services="$(service_names "$profile")" || return 1
  echo "=== 后端 profile: $profile（批次大小 $START_BATCH_SIZE，堆参数 $JVM_OPTS） ==="
  for name in $services; do
    start_service "$java" "$name"
    batch="$batch $name"
    count=$((count + 1))
    if [ "$count" -ge "$START_BATCH_SIZE" ]; then
      wait_for_batch "$batch"
      batch=""
      count=0
    fi
  done
  [ -n "$batch" ] && wait_for_batch "$batch"
}

start_frontend() {
  local pnpm; pnpm="$(socp_pnpm)" || return 1
  if [ -n "$(pid_on_port "$FRONTEND_PORT")" ]; then
    echo "  [已在运行] $FRONTEND_APP :$FRONTEND_PORT"
    return 0
  fi
  if [ ! -d "$FRONTEND/node_modules" ]; then
    echo "  [提示] frontend/node_modules 不存在，先执行： cd frontend && $pnpm install"
    return 1
  fi
  # pnpm locator may contain multiple words (for example: corepack pnpm@10.x).
  ( cd "$FRONTEND" && nohup $pnpm --filter "@socp/app-$FRONTEND_APP" dev --host 0.0.0.0 > "$LOGDIR/frontend-$FRONTEND_APP.log" 2>&1 < /dev/null & )
  echo "  [启动] frontend/$FRONTEND_APP -> :$FRONTEND_PORT  (日志 .cache/frontend-$FRONTEND_APP.log)"
}

stop_backend() {
  local profile="${1:-full}" services name port pid
  services="$(service_names "$profile")" || return 1
  for name in $services; do
    port="$(socp_port "$name")"
    pid="$(pid_on_port "$port")"
    [ -n "$pid" ] && { kill_pid "$pid"; echo "  [停止] $name :$port PID=$pid"; }
  done
}

service_is_known() {
  local candidate="${1:-}"
  case " $SOCP_MODULE_NAMES " in
    *" $candidate "*) return 0 ;;
    *) return 1 ;;
  esac
}

start_one_service() {
  local name="${1:-}" java
  if ! service_is_known "$name"; then
    echo "未知服务: $name" >&2
    return 1
  fi
  java="$(socp_java)" || return 1
  start_service "$java" "$name"
  wait_for_batch "$name"
}

stop_one_service() {
  local name="${1:-}" port pid
  if ! service_is_known "$name"; then
    echo "未知服务: $name" >&2
    return 1
  fi
  port="$(socp_port "$name")"
  pid="$(pid_on_port "$port")"
  if [ -n "$pid" ]; then
    kill_pid "$pid"
    echo "  [停止] $name :$port PID=$pid"
  else
    echo "  [未运行] $name :$port"
  fi
}

stop_all() {
  echo "=== 停止后端 ==="
  stop_backend full
  # Also stop optional standalone compatibility collectors if they were started explicitly.
  stop_one_service asset-collect
  stop_one_service hips-collect
  echo "=== 停止前端 ==="
  local pid
  pid="$(pid_on_port "$FRONTEND_PORT")"
  [ -n "$pid" ] && { kill_pid "$pid"; echo "  [停止] $FRONTEND_APP :$FRONTEND_PORT PID=$pid"; }
  return 0
}

status_all() {
  local profile="${1:-full}" name port code up=0 total=0 services
  services="$(service_names "$profile")" || return 1
  echo "=== 后端服务 ==="
  for name in $services; do
    total=$((total + 1))
    port="$(socp_port "$name")"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$(socp_health_url "$name")" 2>/dev/null)
    if [ "$code" = "200" ]; then
      up=$((up + 1)); echo "  ✅ $name  :$port  -> UP"
    else
      echo "  ❌ $name  :$port  -> ${code:-no-response}"
    fi
  done
  echo "  --- $up / $total UP ($profile) ---"
  echo "=== 前端 ==="
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$FRONTEND_PORT/" 2>/dev/null)
  [ "$code" = "200" ] && echo "  ✅ $FRONTEND_APP  :$FRONTEND_PORT  -> OK" \
                      || echo "  ❌ $FRONTEND_APP  :$FRONTEND_PORT  -> ${code:-no-response}"
}

# sleep 在部分受限 shell 里不可用，用 python 兜底
nap() { sleep "$1" 2>/dev/null || python -c "import time;time.sleep($1)" 2>/dev/null || true; }

case "${1:-start}" in
  doctor)   doctor ;;
  start)
    profile="${2:-full}"
    start_backend "$profile"
    echo "=== 启动前端 (workbench) ==="; start_frontend
    echo "=== 等待启动（8s）==="; nap 8
    echo "=== 状态 ==="; status_all "$profile"
    ;;
  backend)  profile="${2:-full}"; start_backend "$profile"; status_all "$profile" ;;
  frontend) echo "=== 启动前端 ==="; start_frontend; nap 3; status_all ;;
  start-service) start_one_service "${2:-}" ;;
  stop-service)  stop_one_service "${2:-}" ;;
  restart)  profile="${2:-full}"; stop_all; start_backend "$profile"; start_frontend; status_all "$profile" ;;
  stop)     stop_all ;;
  status)   status_all "${2:-full}" ;;
  *)
    echo "用法: $0 {doctor|start [core|ui|full]|stop|status [core|ui|full]|backend [core|ui|full]|frontend|start-service <name>|stop-service <name>|restart [core|ui|full]}"
    exit 1
    ;;
esac
