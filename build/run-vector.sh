#!/usr/bin/env bash
# =============================================================================
# SOCP 采集 Agent（Vector）启动器
#
# 使用仓库固定源码 + disk-buffer 修复构建的镜像；不自动回退到官方未修复二进制。
# 首次运行前：bash build/vector-runtime.sh image
# 升级/回滚与旧状态保留见 docs/operations/vector-runtime.md。
#
# 用法：
#   bash build/run-vector.sh start      # 创建新采集器；已有容器不会被替换
#   bash build/run-vector.sh stop       # 停止并保留容器及其状态
#   bash build/run-vector.sh status     # 状态
#
# 环境变量：SOCP_VECTOR_TOKEN（默认 dev-vector-token，须与 search-config
# socp.security.ingest-token 一致，否则 ingest 401）。
# =============================================================================
set -euo pipefail

NAME="socp-vector"
ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=../infra/middleware-images.env
source "$ROOT/infra/middleware-images.env"
IMAGE="${SOCP_VECTOR_IMAGE:?SOCP_VECTOR_IMAGE is missing from infra/middleware-images.env}"
CONFIG="$ROOT/agents/vector-pipeline/vector.toml"
TOKEN="${SOCP_VECTOR_TOKEN:-dev-vector-token}"

case "${1:-start}" in
  start)
    if docker ps -a --format '{{.Names}}' | grep -q "^$NAME$"; then
      echo "$NAME 已存在；不会自动停止、删除或替换它。" >&2
      echo "升级前保留原镜像、配置和 data_dir；参见 docs/operations/vector-runtime.md。" >&2
      exit 1
    fi
    # Fail closed if the local source-built image is missing or provenance differs.
    # This command never pulls the similarly numbered official release as a fallback.
    bash "$ROOT/build/vector-runtime.sh" verify-image
    # Vector 配置模板的 ${VAR} 环境变量替换不可靠（实测不展开），改用 sed 注入 token 生成运行配置
    GEN="$ROOT/.cache/vector.generated.toml"
    mkdir -p "$ROOT/.cache"
    # Escape sed replacement characters so generated credentials may contain
    # '/', '&', or '\\' without corrupting the TOML or the replacement command.
    ESCAPED_TOKEN="${TOKEN//\\/\\\\}"
    ESCAPED_TOKEN="${ESCAPED_TOKEN//&/\\&}"
    ESCAPED_TOKEN="${ESCAPED_TOKEN//\//\\/}"
    sed "s/__VECTOR_TOKEN__/$ESCAPED_TOKEN/g" "$CONFIG" > "$GEN"
    # Never print the collector credential; CI logs and local shell history are
    # routinely retained outside the process that starts Vector.
    echo "启动 $NAME （collector credential configured）"
    # MSYS_NO_PATHCONV=1：禁止 Git Bash 把容器内 POSIX 路径（/etc/...）转换成 Windows 路径。
    # 用默认 bridge 网络 + host.docker.internal 访问宿主服务（--network host 在 Windows Docker Desktop
    # 下容器内 localhost 并不指向宿主）；syslog 端口 5514 显式映射供外部投递。
    MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" \
      -v "$GEN":/etc/vector/vector.toml:ro \
      -v "$ROOT/demo":/demo \
      -p 5514:5514/tcp \
      --memory 256m \
      --log-driver json-file \
      --log-opt max-size=10m \
      --log-opt max-file=3 \
      "$IMAGE" --config /etc/vector/vector.toml
    echo "日志: docker logs -f $NAME"
    ;;
  stop)
    docker stop --time 60 "$NAME"
    echo "已停止 ${NAME}；容器、配置和磁盘状态未删除。"
    echo "恢复、升级或回滚前核对容器镜像与保留的状态；参见 docs/operations/vector-runtime.md。"
    ;;
  status)
    docker ps -a --filter "name=$NAME" --format "{{.Names}}\t{{.Status}}"
    ;;
  *)
    echo "用法: $0 {start|stop|status}"
    exit 1
    ;;
esac
