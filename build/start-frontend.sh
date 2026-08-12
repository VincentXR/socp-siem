#!/usr/bin/env bash
# =============================================================================
# SOCP 前端 dev server 启动器（2026-08-12 新增）
#
# 为什么必须从"物理路径"启动：
#   vite 6.4.3 在 cwd 是 junction（如 D:\...\siem\socp -> C:\socp-siem）时，
#   模块 resolved id 变成 C 盘真实路径，但 fs 存在性检查失败 -> 依赖预构建改写
#   不生效（import 'vue' 保持裸导入）-> 浏览器报 "Failed to resolve module
#   specifier" -> 白屏。从真实路径启动则一切正常。
#   （本脚本用 `cd -P` 自动把 junction 解析成物理路径，换盘/换布局也不用改）
#
# 用法：
#   bash build/start-frontend.sh            # 默认 5173
#   bash build/start-frontend.sh 5174       # 指定端口
# =============================================================================
set -euo pipefail

PORT="${1:-5173}"

# 仓库根（-P 解析 junction -> 物理路径；本文件在 <repo>/build/ 下）
ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Windows：node.exe 不认 /c/ 风格 POSIX 路径，转成盘符路径 C:/...（同 run-all.sh 的坑）
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -m "$ROOT")"
else
  ROOT="$(printf '%s:%s' "${ROOT:1:1}" "${ROOT:2}")"
fi
WORKBENCH="$ROOT/frontend/apps/workbench"
VITE_BIN="$ROOT/frontend/node_modules/vite/bin/vite.js"

if [ ! -f "$VITE_BIN" ]; then
  echo "缺少 vite: $VITE_BIN （先 cd frontend && pnpm install）" >&2
  exit 1
fi
if [ ! -f "$WORKBENCH/src/main.ts" ]; then
  echo "缺少前端源码: $WORKBENCH/src/main.ts" >&2
  exit 1
fi

NODE="$(command -v node 2>/dev/null || echo "C:/Users/Ran/.workbuddy/binaries/node/versions/22.22.2/node.exe")"

echo "启动前端 dev server: 端口 $PORT  根目录 $(cd -P "$WORKBENCH" && pwd)"
cd -P "$WORKBENCH"
exec "$NODE" "$VITE_BIN" --port "$PORT" --strictPort
