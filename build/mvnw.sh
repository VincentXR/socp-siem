#!/usr/bin/env bash
# SOCP 构建入口（Git Bash / Windows）
#
# 背景（踩过的坑，勿改回去）：
#   1. Windows 上的 java 不认 Git Bash 的 POSIX 路径（/d/xxx），必须用盘符路径 D:/xxx；
#   2. 环境里存在损坏的 ~/.mavenrc 与错误的 JAVA_HOME，mvn 脚本会被带偏，
#      所以这里绕过 mvn 脚本，直接拉起 plexus-classworlds Launcher；
#   3. 中央仓库在本机只有 ~5 kB/s，必须用 build/settings-mirror.xml 走阿里云镜像。
#
# 用法： bash socp/build/mvnw.sh compile
#        bash socp/build/mvnw.sh -pl services/alert-web -am package -DskipTests

set -euo pipefail

ROOT="D:/Program Files (x86)/WorkBuddy/siem"
JH="$ROOT/tooling/jdk-21.0.12+8"
MV="$ROOT/tooling/apache-maven-3.9.9"
PROJECT="$ROOT/socp"
SETTINGS="$PROJECT/build/settings-mirror.xml"

exec "$JH/bin/java" \
  -Duser.language=en -Duser.country=US \
  -cp "$MV/boot/plexus-classworlds-2.8.0.jar" \
  -Dmaven.home="$MV" \
  -Dclassworlds.conf="$MV/bin/m2.conf" \
  -Dmaven.multiModuleProjectDirectory="$PROJECT" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  -B -s "$SETTINGS" -f "$PROJECT/pom.xml" "$@"
