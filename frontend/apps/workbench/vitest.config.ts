import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    // Each jsdom worker loads Vue/Element Plus. Keep memory and CPU bounded
    // when this suite runs alongside the backend and browser checks.
    maxWorkers: 2,
    clearMocks: true,
    include: ['scripts/**/*.component.test.ts', 'src/components/soar/editor/**/*.test.ts', 'src/lib/**/*.test.ts', 'src/api/**/*.test.ts'],
    server: {
      deps: { inline: ['element-plus'] },
    },
  },
})
