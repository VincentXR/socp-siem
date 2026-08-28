#!/usr/bin/env bash
# Start/stop the fixed three-instance Detection evidence environment.
# All instances share the same Kafka group and PostgreSQL store.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/toolchain.sh"
source "$SCRIPT_DIR/ports.env"

PORTS_RAW="${SOCP_DETECT_CLUSTER_PORTS:-${SOCP_PORT_DETECT_WEB},28082,38082}"
GROUP_ID="${SOCP_KAFKA_GROUP_ID:-socp-detect}"
PROFILE="${SOCP_DETECT_PROFILE:-pg}"
JVM_OPTS="${SOCP_JVM_OPTS:--Xms32m -Xmx256m}"
LOGDIR="$ROOT/.cache/detection-cluster"
JAR="$ROOT/services/detect-web/target/detect-web-1.0.0-SNAPSHOT.jar"

csv_ports() {
  printf '%s\n' "$PORTS_RAW" | tr ',' ' '
}

pid_on_port() {
  if [ "${OS:-}" = "Windows_NT" ] && command -v netstat >/dev/null 2>&1; then
    netstat -ano 2>/dev/null | grep -i listen | grep ":$1 " | awk '{print $NF}' | head -1
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti tcp:"$1" -s tcp:LISTEN 2>/dev/null | head -1
  elif command -v ss >/dev/null 2>&1; then
    ss -ltnp "sport = :$1" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -1
  elif command -v netstat >/dev/null 2>&1; then
    netstat -lntp 2>/dev/null | awk -v port=":$1" '$4 ~ (port "$") {sub(/\/.*/, "", $7); print $7; exit}'
  fi
}

stop_port() {
  local port pid pid_file launcher_pid_file candidate
  port="$1"
  pid_file="$LOGDIR/detect-$port.pid"
  launcher_pid_file=""
  if [ "$port" = "$SOCP_PORT_DETECT_WEB" ]; then
    launcher_pid_file="$ROOT/.cache/detect-web.pid"
  fi
  pid=""
  for candidate in "$pid_file" "$launcher_pid_file"; do
    [ -n "$candidate" ] && [ -r "$candidate" ] || continue
    pid="$(sed -n '1{s/[^0-9].*//;p;}' "$candidate")"
    if [ -z "$pid" ] || ! kill -0 "$pid" >/dev/null 2>&1; then pid=""; fi
    [ -n "$pid" ] && break
  done
  [ -n "$pid" ] || pid="$(pid_on_port "$port" || true)"
  if [ -n "$pid" ]; then
    if command -v taskkill >/dev/null 2>&1; then
      MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" >/dev/null 2>&1 || true
    else
      kill "$pid" >/dev/null 2>&1 || true
    fi
  fi
  rm -f "$pid_file"
  if [ -n "$launcher_pid_file" ]; then
    rm -f "$launcher_pid_file"
  fi
}

stop_cluster() {
  local port
  for port in $(csv_ports); do stop_port "$port"; done
}

start_cluster() {
  [ -f "$JAR" ] || { echo "missing Detection jar: $JAR" >&2; exit 1; }
  mkdir -p "$LOGDIR"
  # The canonical instance may have been started by run-all.sh. Reconcile all
  # three ports into one known cluster before the evidence run.
  stop_cluster
  local port index=0 java
  java="$(socp_java)"
  for port in $(csv_ports); do
    index=$((index + 1))
    nohup "$java" $JVM_OPTS -jar "$JAR" \
      --server.port="$port" \
      --spring.profiles.active="$PROFILE" \
      --socp.kafka.group-id="$GROUP_ID" \
      > "$LOGDIR/detect-$port.log" 2>&1 < /dev/null &
    echo $! > "$LOGDIR/detect-$port.pid"
  done
  local elapsed=0 healthy=0
  while [ "$elapsed" -lt "${SOCP_DETECT_CLUSTER_TIMEOUT:-120}" ]; do
    healthy=1
    for port in $(csv_ports); do
      if ! curl -fsS --max-time 2 "http://127.0.0.1:$port/detect-web/actuator/health" >/dev/null 2>&1; then
        healthy=0
        break
      fi
    done
    [ "$healthy" -eq 1 ] && break
    sleep 2
    elapsed=$((elapsed + 2))
  done
  if [ "$healthy" -ne 1 ]; then
    echo "Detection cluster did not become healthy" >&2
    return 1
  fi
  echo "Detection cluster UP: $PORTS_RAW group=$GROUP_ID profile=$PROFILE"
}

case "${1:-status}" in
  start) start_cluster ;;
  stop) stop_cluster ;;
  restart) stop_cluster; start_cluster ;;
  status)
    for port in $(csv_ports); do
      if [ -n "$(pid_on_port "$port" || true)" ]; then echo "UP $port"; else echo "DOWN $port"; fi
    done
    ;;
  *) echo "usage: $0 {start|stop|restart|status}" >&2; exit 1 ;;
esac
