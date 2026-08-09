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
    port: 5173,
    proxy: {
      '/alert-web': { target: 'http://localhost:18080', changeOrigin: true },
      '/search-config': { target: 'http://localhost:18081', changeOrigin: true },
      '/detect-web': { target: 'http://localhost:18082', changeOrigin: true },
      '/soar-web': { target: 'http://localhost:18083', changeOrigin: true },
      '/report-web': { target: 'http://localhost:18084', changeOrigin: true },
      '/asset-web': { target: 'http://localhost:18085', changeOrigin: true },
      '/soc-base': { target: 'http://localhost:18086', changeOrigin: true },
      '/hips-web': { target: 'http://localhost:18087', changeOrigin: true },
      '/ai-assistant': { target: 'http://localhost:18088', changeOrigin: true },
      '/detect-model': { target: 'http://localhost:18090', changeOrigin: true },
      '/threat-web': { target: 'http://localhost:18094', changeOrigin: true },
      '/attack-web': { target: 'http://localhost:18095', changeOrigin: true },
      '/notify-web': { target: 'http://localhost:18096', changeOrigin: true },
      '/incident-web': { target: 'http://localhost:18097', changeOrigin: true },
      '/auth': { target: 'http://localhost:18092', changeOrigin: true },
      '/actuator': { target: 'http://localhost:18092', changeOrigin: true },
    },
  },
})
