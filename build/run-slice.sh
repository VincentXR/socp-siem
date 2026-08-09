#!/usr/bin/env bash
# 启动 SOCP 本地纵切切片：alert-web(18080) + search-config(18081) + detect-web(18082) + api-gateway(18092)
#
# 【为什么端口要用命令行参数显式传】
#   本机环境里存在 SERVER__PORT=0 这个环境变量，Spring Boot 宽松绑定会把它折叠成
#   server.port=0，导致服务起在随机端口，且 application.yml 里的配置被环境变量压过。
#   Spring Boot 属性优先级：命令行参数 > 环境变量 > application.yml，
#   所以这里必须用 --server.port=xxx 才能稳定生效。
#
# 用法： bash socp/build/run-slice.sh start   # 启动
#        bash socp/build/run-slice.sh stop    # 停止
#        bash socp/build/run-slice.sh status  # 探活

set -uo pipefail

ROOT="D:/Program Files (x86)/WorkBuddy/siem"
JAVA="$ROOT/tooling/jdk-21.0.12+8/bin/java"
SSA_JAR="$ROOT/socp/services/alert-web/target/alert-web-1.0.0-SNAPSHOT.jar"
GLS_JAR="$ROOT/socp/services/search-config/target/search-config-1.0.0-SNAPSHOT.jar"
GAS_JAR="$ROOT/socp/services/detect-web/target/detect-web-1.0.0-SNAPSHOT.jar"
GW_JAR="$ROOT/socp/services/api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar"
LOGDIR="$ROOT/.cache"
SSA_PORT=18080
GLS_PORT=18081
GAS_PORT=18082
GW_PORT=18092

mkdir -p "$LOGDIR"

case "${1:-start}" in
  start)
    echo "启动 alert-web   -> http://localhost:$SSA_PORT/alert-web"
    "$JAVA" -jar "$SSA_JAR" --server.port=$SSA_PORT > "$LOGDIR/alert-web.log" 2>&1 &
    echo "启动 search-config -> http://localhost:$GLS_PORT/search-config"
    "$JAVA" -jar "$GLS_JAR" --server.port=$GLS_PORT > "$LOGDIR/search-config.log" 2>&1 &
    echo "启动 detect-web    -> http://localhost:$GAS_PORT/detect-web"
    "$JAVA" -jar "$GAS_JAR" --server.port=$GAS_PORT > "$LOGDIR/detect-web.log" 2>&1 &
    echo "启动 gateway    -> http://localhost:$GW_PORT"
    # 下游地址走 SOCP_SSA_URI 环境变量（yml 里的占位符），不要用命令行覆盖 routes[0].uri：
    # Spring Boot 绑定 List 时只取优先级最高的那个属性源，命令行里只写 uri 会导致
    # predicates 整个丢失，启动直接报 "Property: routes[0].predicates Value: []"。
    SOCP_SSA_URI="http://localhost:$SSA_PORT" \
      "$JAVA" -jar "$GW_JAR" --server.port=$GW_PORT > "$LOGDIR/gateway.log" 2>&1 &
    echo "日志：$LOGDIR/{alert-web,search-config,detect-web,gateway}.log"
    ;;
  stop)
    for p in $SSA_PORT $GLS_PORT $GAS_PORT $GW_PORT; do
      pid=$(netstat -ano -p tcp 2>/dev/null | grep -i listen | grep ":$p " | awk '{print $NF}' | head -1)
      if [ -n "${pid:-}" ]; then
        echo "停止端口 $p 上的进程 PID=$pid"
        # MSYS_NO_PATHCONV=1 必须加：否则 Git Bash 会把 /F /PID 当成路径去转换，taskkill 报无效参数
        MSYS_NO_PATHCONV=1 taskkill /F /PID "$pid" > /dev/null 2>&1
      fi
    done
    ;;
  status)
    for p in $SSA_PORT $GLS_PORT $GAS_PORT $GW_PORT; do
      code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:$p/actuator/health" 2>/dev/null || echo down)
      echo "port $p -> $code"
    done
    ;;
  *)
    echo "用法: $0 {start|stop|status}"; exit 1;;
esac
