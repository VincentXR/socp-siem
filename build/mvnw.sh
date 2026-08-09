#!/usr/bin/env bash
# SOCP 构建入口（跨平台：Windows Git Bash / macOS / Linux / CI runner）
#
# 背景（踩过的坑，勿改回去）：
#   1. Windows 上的 java 不认 Git Bash 的 POSIX 路径（/d/xxx），必须用盘符路径 D:/xxx（正斜杠即可）；
#   2. Windows 环境里存在损坏的 ~/.mavenrc 与错误的 JAVA_HOME，mvn 脚本会被带偏，
#      所以本机直接拉起 plexus-classworlds Launcher（绕过 mvn 脚本）；
#   3. 本机中央仓库慢，用 build/settings-mirror.xml 走阿里云镜像。
#
# 用法： bash build/mvnw.sh compile
#        bash build/mvnw.sh -pl services/alert-web -am package -DskipTests

set -euo pipefail

# 仓库根目录（含 build/pom.xml）：脚本位于 <root>/build/
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$SCRIPT_DIR/.." && pwd)"
SETTINGS="$PROJECT/build/settings-mirror.xml"
# tooling 目录在仓库的上一级（本机：siem/tooling/ 含 JDK21 + Maven3.9.9）
TOOLING="$(cd "$SCRIPT_DIR/../.." && pwd)/tooling"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    # ---------- Windows：用仓库上一级 tooling（自带 JDK21 + Maven 3.9.9） ----------
    JH="$TOOLING/jdk-21.0.12+8"
    MV="$TOOLING/apache-maven-3.9.9"
    # POSIX 路径 /d/xxx → 盘符 D:/xxx（正斜杠，java 可识别）
    w() { echo "$1" | sed 's|^/\([a-z]\)/|\1:/|'; }
    JH_W="$(w "$JH")"; MV_W="$(w "$MV")"; PROJ_W="$(w "$PROJECT")"
    exec "$JH_W/bin/java" \
      -Duser.language=en -Duser.country=US \
      -cp "$MV_W/boot/plexus-classworlds-2.8.0.jar" \
      -Dmaven.home="$MV_W" \
      -Dclassworlds.conf="$MV_W/bin/m2.conf" \
      -Dmaven.multiModuleProjectDirectory="$PROJ_W" \
      org.codehaus.plexus.classworlds.launcher.Launcher \
      -B -s "$PROJ_W/build/settings-mirror.xml" -f "$PROJ_W/pom.xml" "$@"
    ;;
  *)
    # ---------- Unix（GitHub Actions 等）：优先 PATH 的 mvn（setup-java/setup-maven 提供） ----------
    if command -v mvn >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
      exec mvn -B -s "$SETTINGS" -f "$PROJECT/pom.xml" "$@"
    fi
    # 回退：仓库上一级 tooling（本地 macOS/Linux 开发）
    if [ -x "$TOOLING/jdk-21.0.12+8/bin/java" ] && [ -d "$TOOLING/apache-maven-3.9.9" ]; then
      exec "$TOOLING/jdk-21.0.12+8/bin/java" \
        -Duser.language=en -Duser.country=US \
        -cp "$TOOLING/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar" \
        -Dmaven.home="$TOOLING/apache-maven-3.9.9" \
        -Dclassworlds.conf="$TOOLING/apache-maven-3.9.9/bin/m2.conf" \
        -Dmaven.multiModuleProjectDirectory="$PROJECT" \
        org.codehaus.plexus.classworlds.launcher.Launcher \
        -B -s "$SETTINGS" -f "$PROJECT/pom.xml" "$@"
    fi
    echo "mvnw.sh: 未找到可用 mvn/java（PATH 或 tooling/），请安装 Maven 3.9 + JDK 21" >&2
    exit 1
    ;;
esac
