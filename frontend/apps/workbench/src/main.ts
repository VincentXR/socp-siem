import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import 'element-plus/es/components/base/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import App from './App.vue'
import './styles/tokens.css'
import './styles.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5_000,
      gcTime: 5 * 60_000,
      retry: 2,
      retryDelay: attempt => Math.min(30_000, 1_000 * 2 ** attempt),
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
      refetchIntervalInBackground: false,
    },
  },
})

createApp(App)
  .use(createPinia())
  .use(VueQueryPlugin, { queryClient })
  .mount('#app')
