import { ref } from 'vue'
import { setChartTheme } from '../lib/echarts'

export type Theme = 'light' | 'dark'

export function useTheme() {
  const theme = ref<Theme>('light')

  function applyTheme(next: Theme) {
    theme.value = next
    document.documentElement.setAttribute('data-theme', next)
    try { localStorage.setItem('socp_theme', next) } catch { /* ignore */ }
    setChartTheme(next)
  }

  function initTheme() {
    let next: Theme | null = null
    try { next = localStorage.getItem('socp_theme') as Theme | null } catch { /* ignore */ }
    if (!next) next = window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    applyTheme(next)
  }

  function toggleTheme() {
    applyTheme(theme.value === 'light' ? 'dark' : 'light')
  }

  return { theme, applyTheme, initTheme, toggleTheme }
}
