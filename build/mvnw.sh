#!/usr/bin/env bash
# SOCP 构建入口（跨平台：Windows Git Bash / macOS / Linux / CI runner）
#
# 查找顺序（所有平台一致，不再把某台机器的目录结构当项目规范）：
#   1. PATH 里的 mvn + 可用 java（JAVA_HOME 或 PATH）
#   2. 仓库自带 <repo>/tooling/{jdk-21.0.12+8, apache-maven-3.9.9}
#   3. 仓库上一级 ../tooling（历史布局兼容）
#   4. 都没有 -> 打印明确的安装提示后退出
#
# 背景（踩过的坑，勿改回去）：
#   1. Windows 上的 java 不认 Git Bash 的 POSIX 路径（/d/xxx），必须用盘符路径 D:/xxx；
#   2. Windows 环境里可能存在损坏的 ~/.mavenrc 与错误的 JAVA_HOME，mvn 脚本会被带偏，
#      所以走 tooling 分支时直接拉起 plexus-classworlds Launcher（绕过 mvn 脚本）；
#   3. 国内拉中央仓库慢，用 build/settings-mirror.xml 走阿里云镜像
#      （不想用镜像：SOCP_MAVEN_SETTINGS=/path/to/settings.xml 覆盖，或设为 none 走默认）。
#
# 用法： bash build/mvnw.sh -DskipTests package
#        bash build/mvnw.sh -pl services/alert-web -am clean package

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=toolchain.sh
source "$SCRIPT_DIR/toolchain.sh"   # -> SOCP_ROOT / socp_java / socp_maven / socp_win_path

PROJECT="$SOCP_ROOT"
SETTINGS="${SOCP_MAVEN_SETTINGS:-$PROJECT/build/settings-mirror.xml}"

SETTINGS_ARGS=()
if [ "$SETTINGS" != "none" ] && [ -f "$SETTINGS" ]; then
  SETTINGS_ARGS=(-s "$SETTINGS")
fi

MAVEN="$(socp_maven)"          # "path|/usr/bin/mvn" 或 "home|/…/apache-maven-3.9.9"
MAVEN_KIND="${MAVEN%%|*}"
MAVEN_LOC="${MAVEN#*|}"

if [ "$MAVEN_KIND" = "path" ]; then
  # ---- PATH 上有 mvn：直接用（CI / 使用者自己装的环境） ----
  if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_BIN="$(socp_java)"
    # java 在 <home>/bin/java -> 反推 JAVA_HOME，让 mvn 找得到编译器
    case "$JAVA_BIN" in
      */bin/java) JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"; export JAVA_HOME ;;
    esac
  fi
  exec "$MAVEN_LOC" -B "${SETTINGS_ARGS[@]}" -f "$PROJECT/pom.xml" "$@"
fi

# ---- 走仓库自带 tooling：绕过 mvn 脚本，直接拉 plexus-classworlds Launcher ----
JAVA_BIN="$(socp_java)"
MV="$MAVEN_LOC"
PROJ="$(socp_win_path "$PROJECT")"
SETTINGS_W=()
[ ${#SETTINGS_ARGS[@]} -gt 0 ] && SETTINGS_W=(-s "$(socp_win_path "$SETTINGS")")

BOOT_JAR="$(ls "$MV"/boot/plexus-classworlds-*.jar 2>/dev/null | head -1)"
if [ -z "$BOOT_JAR" ]; then
  echo "mvnw.sh: $MV/boot 下找不到 plexus-classworlds jar，tooling 目录可能不完整" >&2
  exit 1
fi

exec "$JAVA_BIN" \
  -Duser.language=en -Duser.country=US \
  -cp "$(socp_win_path "$BOOT_JAR")" \
  -Dmaven.home="$MV" \
  -Dclassworlds.conf="$MV/bin/m2.conf" \
  -Dmaven.multiModuleProjectDirectory="$PROJ" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -B "${SETTINGS_W[@]}" -f "$PROJ/pom.xml" "$@"
