import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  build: {
    // dist 由调用方负责清理（rm -rf），避免构建期删除触发运行时安全删除 shim 的路径问题
    emptyOutDir: false,
    // 代码分割（2026-08-10）：echarts / element-plus / vue 框架 / 其余依赖 独立 chunk，
    // 大依赖单独长缓存，首屏只加载用到的 chunk
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/echarts') || id.includes('node_modules/zrender')) return 'echarts'
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) return 'element-plus'
          if (id.includes('node_modules/vue') || id.includes('node_modules/@vue')) return 'vue-vendor'
          if (id.includes('node_modules')) return 'vendor'
        },
      },
    },
  },
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: Number(process.env.SOCP_PORT_FRONTEND_WORKBENCH ?? 5173),
    proxy: {
      // ---------------------------------------------------------------------
      // 前端只知道「网关」一个地址，不知道后端有几个服务、各自在哪个端口。
      //
      // 以前这里维护 17 条 localhost:180xx 代理，等于把整个服务拓扑泄漏到前端，
      // 后端换端口/上 Docker/加服务都要回来改这个文件。现在一条正则全代到网关，
      // 由 api-gateway 按 context-path 路由到下游 —— 拓扑知识只存在于网关一处。
      //
      // 生产构建不走 vite proxy：前端与网关同源部署（Nginx / 网关直接托管静态资源）。
      // ---------------------------------------------------------------------
      '^/(alert-web|search-config|detect-web|soar-web|report-web|asset-web|soc-base|hips-web|ai-assistant|detect-model|asset-collect|hips-collect|threat-web|attack-web|notify-web|incident-web|auth|actuator)(/|$)': {
        target: process.env.SOCP_GATEWAY_URL ?? 'http://localhost:18092',
        changeOrigin: true,
      },
    },
  },
})
