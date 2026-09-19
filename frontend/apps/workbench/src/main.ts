import { createApp } from 'vue'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import 'element-plus/es/components/base/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import App from './App.vue'
import { router } from './app/router'
import { i18n } from './i18n'
import { initializeLocale } from './i18n/locale-manager'
import { ApiError, isAbortError } from './api/core'
import './styles/tokens.css'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      gcTime: 5 * 60_000,
      retry: (failureCount, error) => {
        if (isAbortError(error)) return false
        if (error instanceof ApiError && [401, 403, 422].includes(error.status)) return false
        return failureCount < 2
      },
      retryDelay: attempt => Math.min(30_000, 1_000 * 2 ** attempt),
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      refetchIntervalInBackground: false,
    },
  },
})

initializeLocale()

createApp(App)
  .use(i18n)
  .use(router)
  .use(VueQueryPlugin, { queryClient })
  .mount('#app')
