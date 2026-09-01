#!/usr/bin/env bash
# ============================================================
# SOCP 发布包打包脚本：把可运行产物打成一个 tar.gz，解压即用。
#
# 内容：
#   - 注册表中的全部后端 fat jar（services/*/target/*.jar）
#   - workbench 前端生产产物（frontend/apps/workbench/dist）
#   - 启停/验证脚本（run-all.sh 已动态定位 ROOT，可直接运行）
#   - docs/ 文档 + FROZEN.md + RELEASE.md 使用说明
#
# 用法：
#   bash socp/build/package-release.sh            # 打包（复用已构建产物）
#   bash socp/build/package-release.sh --build    # 先全量构建再打包
# 产物：dist/socp-siem-YYYYMMDD-HHMM.tar.gz
# ============================================================
set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TS=$(date +%Y%m%d-%H%M)
OUT="$REPO_ROOT/dist"
PKG="$OUT/socp-siem-$TS"
PKGNAME="socp-siem-$TS"

# The executable module registry is the release manifest. Iterating it avoids
# accidentally packaging stale jars left under retired module directories.
source "$REPO_ROOT/build/ports.env"
EXPECTED_JARS=$(awk '{print NF}' <<< "$SOCP_MODULE_NAMES")

if [ "${1:-}" = "--build" ]; then
  echo "=== 全量构建 ==="
  bash "$REPO_ROOT/build/mvnw.sh" -DskipTests package || { echo "构建失败"; exit 1; }
fi

echo "=== 收集产物 ==="
rm -rf "$PKG"; mkdir -p "$PKG/socp"

# 1) 后端 jar
JARS=0
for module in $SOCP_MODULE_NAMES; do
  jar="$REPO_ROOT/services/$module/target/$module-1.0.0-SNAPSHOT.jar"
  [ -f "$jar" ] || { echo "  [错误] 缺少 jar: $module"; continue; }
  rel="services/$module/target/$(basename "$jar")"
  mkdir -p "$PKG/socp/$(dirname "$rel")"
  cp "$jar" "$PKG/socp/$rel"
  JARS=$((JARS+1))
done
echo "  后端 jar: $JARS 个"
if [ "$JARS" -ne "$EXPECTED_JARS" ]; then
  echo "  [错误] 期望 $EXPECTED_JARS 个注册模块，实际仅找到 $JARS 个；请先完成全量构建"
  rm -rf "$PKG"
  exit 1
fi

# 2) 前端产物
DIST="$REPO_ROOT/frontend/apps/workbench/dist"
if [ -d "$DIST" ] && [ -f "$DIST/index.html" ]; then
  mkdir -p "$PKG/socp/frontend/apps/workbench"
  cp -r "$DIST" "$PKG/socp/frontend/apps/workbench/"
  echo "  前端产物: workbench/dist ($(ls "$DIST" | wc -l) 个文件)"
else
  echo "  [警告] 未找到前端构建产物，跳过（cd frontend/apps/workbench && npm run build）"
fi

# 3) 脚本
for f in auth_client.py ports.env ports.py run-all.sh toolchain.sh wait_health.py verify-full.py verify-slice.py verify-pipeline.py; do
  [ -f "$REPO_ROOT/build/$f" ] && { mkdir -p "$PKG/socp/build"; cp "$REPO_ROOT/build/$f" "$PKG/socp/build/"; }
done
# 4) 文档与声明
[ -d "$REPO_ROOT/docs" ] && cp -r "$REPO_ROOT/docs" "$PKG/socp/docs"
[ -f "$REPO_ROOT/FROZEN.md" ] && cp "$REPO_ROOT/FROZEN.md" "$PKG/"

# 5) 生成使用说明
cat > "$PKG/RELEASE.md" <<'EOF'
# SOCP 安全运营平台 · 发布包

## 环境要求
- JDK 21（优先用 JAVA_HOME，其次 tooling/jdk-21.0.12+8，最后 PATH 里的 java）
- 前端静态托管：任意静态服务器（python http.server / nginx）即可

## 启动 / 停止 / 状态
```bash
bash socp/build/run-all.sh backend   # 启动默认 15 个进程（采集入口已并入领域服务）
bash socp/build/run-all.sh status full    # 全栈探活
bash socp/build/run-all.sh stop      # 停止
```

## 前端
构建产物在 `socp/frontend/apps/workbench/dist`，静态托管后访问：
```bash
python -m http.server 5188 -d socp/frontend/apps/workbench/dist
```
（生产建议 nginx 托管该目录并把 /xxx-web 反向代理到对应端口）

## 端到端验证
```bash
python socp/build/verify-full.py     # 62 项断言（健康/情报/ATT&CK/全链路/UEBA/接入任务/持久化）
python socp/build/verify-slice.py    # 18 项经网关断言
```

## 端口速查
alert-web 18080 · search-config 18081 · detect-web 18082 · soar-web 18083 · report-web 18084 ·
asset-web 18085 · soc-base 18086 · hips-web 18087 · ai-assistant 18088 · detect-model 18090 ·
api-gateway 18092 · threat-web 18094 · attack-web 18095 ·
notify-web 18096 · incident-web 18097

旧 `/asset-collect/**` 与 `/hips-collect/**` URL 由网关转发到对应领域服务，不再发布独立采集进程。
EOF

echo "=== 打包 ==="
cd "$OUT"
tar czf "$PKGNAME.tar.gz" "$PKGNAME" 2>/dev/null
SIZE=$(ls -lh "$PKGNAME.tar.gz" | awk '{print $5}')
echo "  生成: $OUT/$PKGNAME.tar.gz ($SIZE)"
echo "=== 内容清单 ==="
tar tzf "$PKGNAME.tar.gz" | sed "s|$PKGNAME/||" | awk -F/ '{print $1"/"$2"/"$3}' | sort -u | head -n 40
echo "  ... ($(tar tzf "$PKGNAME.tar.gz" | wc -l) 个文件)"
rm -rf "$PKG" 2>/dev/null || true
