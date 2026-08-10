# shellcheck shell=bash
# =============================================================================
# SOCP 工具链定位（java / maven / pnpm）—— 所有脚本共用，禁止再出现硬编码绝对路径
# =============================================================================
# 设计原则：
#   1. 路径一律从脚本自身位置推导，不假设仓库叫什么名字、放在哪一层；
#   2. 先用使用者机器上已有的工具（JAVA_HOME / PATH），仓库自带的 tooling/ 只是兜底；
#   3. 找不到就报清楚的错（缺什么、怎么装），不要让调用方对着 "command not found" 猜。
#
# source 本文件后可用：
#   SOCP_ROOT     仓库根（含 pom.xml / build/ / services/ / frontend/）
#   socp_java     -> 打印 java 可执行路径
#   socp_pnpm     -> 打印能跑 pnpm 的命令（可能是 "pnpm" / "corepack pnpm@x" / "npx pnpm"）
#   socp_win_path -> /d/xxx 风格转 D:/xxx（Windows 上 java 只认盘符路径）
# =============================================================================

# --- 仓库根：本文件在 <repo>/build/ 下，向上一层就是仓库根 ---------------------
SOCP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# MSYS/Git Bash 的 /d/xxx 风格，java 打不开 -> 转成 D:/xxx
socp_win_path() {
  local p="$1"
  case "$p" in
    /[a-zA-Z]/*)
      if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$p"
      else
        printf '%s:%s' "$(printf '%s' "${p:1:1}" | tr '[:lower:]' '[:upper:]')" "${p:2}"
      fi
      ;;
    *) printf '%s' "$p" ;;
  esac
}

case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*) SOCP_ROOT="$(socp_win_path "$SOCP_ROOT")" ;;
esac

# tooling/ 兜底：优先仓库内 <repo>/tooling，其次仓库上一级 <repo>/../tooling（本机历史布局）
_socp_tooling_dirs() {
  printf '%s\n' "$SOCP_ROOT/tooling" "$(dirname "$SOCP_ROOT")/tooling"
}

# --- java：JAVA_HOME > PATH > 仓库 tooling ------------------------------------
socp_java() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    printf '%s' "$JAVA_HOME/bin/java"; return 0
  fi
  if command -v java >/dev/null 2>&1; then
    printf '%s' "java"; return 0
  fi
  local d
  while read -r d; do
    [ -x "$d/jdk-21.0.12+8/bin/java" ] && { printf '%s' "$(socp_win_path "$d/jdk-21.0.12+8/bin/java")"; return 0; }
  done < <(_socp_tooling_dirs)
  echo "socp: 找不到 java。请安装 JDK 21 并设置 JAVA_HOME，或把 java 放进 PATH。" >&2
  return 1
}

# --- maven home：PATH 的 mvn > 仓库 tooling ------------------------------------
# 输出形如 "path|<mvn 可执行>" 或 "home|<maven 安装目录>"，调用方按前缀分支。
socp_maven() {
  if command -v mvn >/dev/null 2>&1; then
    printf 'path|%s' "$(command -v mvn)"; return 0
  fi
  local d
  while read -r d; do
    [ -d "$d/apache-maven-3.9.9" ] && { printf 'home|%s' "$(socp_win_path "$d/apache-maven-3.9.9")"; return 0; }
  done < <(_socp_tooling_dirs)
  echo "socp: 找不到 Maven。请安装 Maven 3.9+ 放进 PATH，或把 apache-maven-3.9.9 放到 <repo>/tooling/。" >&2
  return 1
}

# --- pnpm：PATH pnpm > corepack > npx ------------------------------------------
# 版本从 frontend/package.json 的 packageManager 字段读，不写死。
socp_pnpm_version() {
  local pj="$SOCP_ROOT/frontend/package.json"
  [ -f "$pj" ] || { printf 'pnpm'; return 0; }
  sed -n 's/.*"packageManager"[[:space:]]*:[[:space:]]*"\(pnpm@[^"]*\)".*/\1/p' "$pj" | head -1
}

socp_pnpm() {
  local want; want="$(socp_pnpm_version)"
  [ -z "$want" ] && want="pnpm"
  if command -v pnpm >/dev/null 2>&1; then
    printf 'pnpm'; return 0
  fi
  if command -v corepack >/dev/null 2>&1; then
    printf 'corepack %s' "$want"; return 0
  fi
  if command -v npx >/dev/null 2>&1; then
    printf 'npx --yes %s' "$want"; return 0
  fi
  echo "socp: 找不到 pnpm/corepack/npx。请安装 Node.js 20+（自带 corepack），或 npm i -g pnpm。" >&2
  return 1
}
