#!/usr/bin/env bash
# SOCP 全栈启停脚本：17 个后端服务 + 1 个前端 dev server（workbench）
#
# 用法（在仓库任意位置执行都可以，路径全部从脚本自身推导）：
#   bash build/run-all.sh doctor    # 环境自检：java / maven / pnpm / jar / 端口
#   bash build/run-all.sh start     # 启动全部后端 + 前端
#   bash build/run-all.sh stop      # 停止全部
#   bash build/run-all.sh status    # 探活
#   bash build/run-all.sh backend   # 只起后端
#   bash build/run-all.sh frontend  # 只起前端
#
# 端口不在本文件里维护 —— 唯一来源是 build/ports.env，改端口只改那一份。
#   例：SOCP_PORT_ALERT_WEB=28080 bash build/run-all.sh backend

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=toolchain.sh
source "$SCRIPT_DIR/toolchain.sh"   # -> SOCP_ROOT / socp_java / socp_pnpm
# shellcheck source=ports.env
source "$SCRIPT_DIR/ports.env"      # -> SOCP_SERVICE_NAMES / socp_port / socp_ctx / 服务间 URL

ROOT="$SOCP_ROOT"
LOGDIR="$ROOT/.cache"
FRONTEND="$ROOT/frontend"
FRONTEND_APP="workbench"
FRONTEND_PORT="$SOCP_PORT_FRONTEND_WORKBENCH"

# JVM 内存：17 个服务统一限堆，避免默认堆吃满机器内存（可用环境变量覆盖）
JVM_OPTS="${SOCP_JVM_OPTS:--Xms64m -Xmx384m}"

# 开发密钥：本机启动注入（生产必须通过环境变量显式提供，禁止使用此默认值）
# 签发与验签必须同值：login-secret 默认 = jwt-secret（否则 /auth/login 签发的 token 业务服务验签失败）
export SOCP_JWT_SECRET="${SOCP_JWT_SECRET:-socp-demo-jwt-secret-0123456789abcdef0123456789abcdef}"
export SOCP_LOGIN_SECRET="${SOCP_LOGIN_SECRET:-$SOCP_JWT_SECRET}"

mkdir -p "$LOGDIR"

jar_of() { printf '%s/services/%s/target/%s-1.0.0-SNAPSHOT.jar' "$ROOT" "$1" "$1"; }

pid_on_port() {
  # Windows(netstat) 与 Unix(lsof) 两条路，找不到就返回空
  if command -v netstat >/dev/null 2>&1 && netstat -ano -p tcp >/dev/null 2>&1; then
    netstat -ano -p tcp 2>/dev/null | grep -i listen | grep ":$1 " | awk '{print $NF}' | head -1
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
  for name in $SOCP_SERVICE_NAMES; do
    [ -f "$(jar_of "$name")" ] || { missing=$((missing + 1)); echo "  ❌ 缺 jar: $name"; }
  done
  if [ "$missing" -eq 0 ]; then
    echo "  ✅ 17/17 jar 就绪"
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
  [ "$busy" -eq 0 ] && echo "  ✅ 17 个端口均空闲"

  echo
  [ "$fatal" -eq 0 ] && echo "自检通过，可以 bash build/run-all.sh start" || echo "存在致命问题，请先处理上面的 ❌"
  return "$fatal"
}

start_backend() {
  local java; java="$(socp_java)" || return 1
  local name port jar
  for name in $SOCP_SERVICE_NAMES; do
    port="$(socp_port "$name")"
    jar="$(jar_of "$name")"
    if [ ! -f "$jar" ]; then
      echo "  [跳过] $name (jar 不存在，先跑 bash build/mvnw.sh -DskipTests package)"
      continue
    fi
    kill_pid "$(pid_on_port "$port")"
    # 网关启用 dev profile 以加载演示账号（application-dev.yml）；生产部署不传 profile 并注入真实密钥
    if [ "$name" = "api-gateway" ]; then
      "$java" $JVM_OPTS -jar "$jar" --server.port="$port" --spring.profiles.active=dev > "$LOGDIR/$name.log" 2>&1 &
    else
      "$java" $JVM_OPTS -jar "$jar" --server.port="$port" > "$LOGDIR/$name.log" 2>&1 &
    fi
    echo "  [启动] $name -> :$port  (日志 .cache/$name.log)"
  done
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
  ( cd "$FRONTEND" && $pnpm --filter "@socp/app-$FRONTEND_APP" dev > "$LOGDIR/frontend-$FRONTEND_APP.log" 2>&1 & )
  echo "  [启动] frontend/$FRONTEND_APP -> :$FRONTEND_PORT  (日志 .cache/frontend-$FRONTEND_APP.log)"
}

stop_all() {
  local name port pid
  echo "=== 停止后端 ==="
  for name in $SOCP_SERVICE_NAMES; do
    port="$(socp_port "$name")"
    pid="$(pid_on_port "$port")"
    [ -n "$pid" ] && { kill_pid "$pid"; echo "  [停止] $name :$port PID=$pid"; }
  done
  echo "=== 停止前端 ==="
  pid="$(pid_on_port "$FRONTEND_PORT")"
  [ -n "$pid" ] && { kill_pid "$pid"; echo "  [停止] $FRONTEND_APP :$FRONTEND_PORT PID=$pid"; }
  return 0
}

status_all() {
  local name port code up=0 total=0
  echo "=== 后端服务 ==="
  for name in $SOCP_SERVICE_NAMES; do
    total=$((total + 1))
    port="$(socp_port "$name")"
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "$(socp_health_url "$name")" 2>/dev/null)
    if [ "$code" = "200" ]; then
      up=$((up + 1)); echo "  ✅ $name  :$port  -> UP"
    else
      echo "  ❌ $name  :$port  -> ${code:-no-response}"
    fi
  done
  echo "  --- $up / $total UP ---"
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
    echo "=== 启动后端 17 服务 ==="; start_backend
    echo "=== 启动前端 (workbench) ==="; start_frontend
    echo "=== 等待启动（8s）==="; nap 8
    echo "=== 状态 ==="; status_all
    ;;
  backend)  echo "=== 启动后端 ==="; start_backend; nap 8; status_all ;;
  frontend) echo "=== 启动前端 ==="; start_frontend; nap 3; status_all ;;
  stop)     stop_all ;;
  status)   status_all ;;
  *)
    echo "用法: $0 {doctor|start|stop|status|backend|frontend}"
    exit 1
    ;;
esac
