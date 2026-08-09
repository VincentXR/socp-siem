#!/usr/bin/env python3
"""生成前端 pnpm monorepo 骨架：packages/{library,soc-ui,dev-deps} + apps/workbench（唯一真实应用）。
注意：search/report/soar/alert 4 个 stub app 已于 2026-08-08 清理，勿再生成。"""
import os

FE = r"D:\Program Files (x86)\WorkBuddy\siem\socp\frontend"

ROOT_PKG = """{
  "name": "socp-frontend",
  "private": true,
  "packageManager": "pnpm@10.0.0",
  "scripts": {
    "dev": "pnpm --filter @socp/app-workbench dev",
    "build": "pnpm --filter @socp/app-workbench build",
    "dev:all": "pnpm -r --parallel dev"
  },
  "devDependencies": {
    "typescript": "5.9.0",
    "vite": "7.0.0",
    "vue-tsc": "2.1.0"
  }
}
"""

WORKSPACE = """packages:
  - 'packages/*'
  - 'apps/*'
"""

# apps: (dir, name, port, title)
APPS = [
    ("workbench", "app-workbench", 5173, "安全运营工作台"),
]

APP_PKG = """{
  "name": "@socp/{NAME}",
  "private": true,
  "version": "1.0.0",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "3.5.0",
    "vue-router": "4.4.0",
    "pinia": "2.2.0",
    "@tanstack/vue-query": "5.59.0",
    "element-plus": "2.8.0",
    "echarts": "5.5.0",
    "@socp/soc-ui": "workspace:*",
    "@socp/library": "workspace:*"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "5.1.0",
    "typescript": "5.9.0",
    "vite": "7.0.0",
    "vue-tsc": "2.1.0"
  }
}
"""

VITE = """import {{ defineConfig }} from 'vite'
import vue from '@vitejs/plugin-vue'
import {{ fileURLToPath, URL }} from 'node:url'

export default defineConfig({{
  plugins: [vue()],
  resolve: {{
    alias: {{ '@': fileURLToPath(new URL('./src', import.meta.url)) }}
  }},
  server: {{
    port: {PORT},
    proxy: {{
      '/alert-web': {{ target: 'http://localhost:8080', changeOrigin: true }},
      '/soc-base': {{ target: 'http://localhost:8080', changeOrigin: true }}
    }}
  }}
}})
"""

TSC = """{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "jsx": "preserve",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client"]
  },
  "include": ["src"]
}
"""

INDEX = """<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>{TITLE}</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
"""

MAIN = """import {{ createApp }} from 'vue'
import {{ createPinia }} from 'pinia'
import {{ VueQueryPlugin }} from '@tanstack/vue-query'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'

createApp(App).use(createPinia()).use(VueQueryPlugin).use(ElementPlus).mount('#app')
"""

APP_VUE = """<script setup lang="ts">
import {{ ref }} from 'vue'
import {{ ElCard, ElButton, ElMessage }} from 'element-plus'

const title = '{TITLE}'
const count = ref(0)
</script>

<template>
  <div style="padding: 24px">
    <el-card>
      <h2>{{ title }}</h2>
      <p>SOCP 前端骨架（Vue3 + TS + Vite + Element Plus + ECharts + TanStack Query）。</p>
      <el-button type="primary" @click="count++; ElMessage.success('ok')">点击 {{ count }}</el-button>
    </el-card>
  </div>
</template>
"""

# packages
LIB_PKG = """{
  "name": "@socp/library",
  "version": "1.0.0",
  "main": "index.ts",
  "types": "index.ts",
  "dependencies": { "element-plus": "2.8.0" }
}
"""
LIB_INDEX = """export * from './components'
"""
LIB_COMP = """export const SocpBrand = () => import('element-plus')
"""

SOCUI_PKG = """{
  "name": "@socp/soc-ui",
  "version": "1.0.0",
  "main": "index.ts",
  "types": "index.ts",
  "dependencies": { "element-plus": "2.8.0", "@socp/library": "workspace:*" }
}
"""
SOCUI_INDEX = """// SOC 共享 UI 组件（布局/表格/图表容器），按 P16 充实
export const version = '1.0.0'
"""

DEVDEPS_PKG = """{
  "name": "@socp/dev-deps",
  "version": "1.0.0",
  "private": true,
  "devDependencies": { "typescript": "5.9.0", "vue-tsc": "2.1.0" }
}
"""

os.makedirs(FE, exist_ok=True)
with open(os.path.join(FE, "package.json"), "w", encoding="utf-8") as f: f.write(ROOT_PKG)
with open(os.path.join(FE, "pnpm-workspace.yaml"), "w", encoding="utf-8") as f: f.write(WORKSPACE)

for pdir, pkg, port, title in APPS:
    d = os.path.join(FE, "apps", pdir)
    src = os.path.join(d, "src"); os.makedirs(src, exist_ok=True)
    with open(os.path.join(d, "package.json"), "w", encoding="utf-8") as f: f.write(APP_PKG.replace("{NAME}", pkg))
    with open(os.path.join(d, "vite.config.ts"), "w", encoding="utf-8") as f: f.write(VITE.format(PORT=port))
    with open(os.path.join(d, "tsconfig.json"), "w", encoding="utf-8") as f: f.write(TSC)
    with open(os.path.join(d, "index.html"), "w", encoding="utf-8") as f: f.write(INDEX.format(TITLE=title))
    with open(os.path.join(src, "main.ts"), "w", encoding="utf-8") as f: f.write(MAIN)
    with open(os.path.join(src, "App.vue"), "w", encoding="utf-8") as f: f.write(APP_VUE.format(TITLE=title))

for pkgdir, pkg, index in [
    ("library", LIB_PKG, LIB_INDEX),
    ("soc-ui", SOCUI_PKG, SOCUI_INDEX),
    ("dev-deps", DEVDEPS_PKG, None),
]:
    d = os.path.join(FE, "packages", pkgdir); os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "package.json"), "w", encoding="utf-8") as f: f.write(pkg)
    if index is not None:
        with open(os.path.join(d, "index.ts"), "w", encoding="utf-8") as f: f.write(index)
    if pkgdir == "library":
        os.makedirs(os.path.join(d, "components"), exist_ok=True)
        with open(os.path.join(d, "components", "index.ts"), "w", encoding="utf-8") as f: f.write(LIB_COMP)

print("frontend monorepo skeleton generated")
