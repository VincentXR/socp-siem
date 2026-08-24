import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    clearMocks: true,
    include: ['scripts/**/*.component.test.ts'],
    server: {
      deps: { inline: ['element-plus'] },
    },
  },
})
