import { ref } from 'vue'
import { setChartTheme } from '../lib/echarts'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'socp_theme'
/** 系统偏好监听只绑定一次，避免每个调用方重复注册。 */
let followSystemBound = false

function storedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return value === 'dark' || value === 'light' ? value : null
  } catch {
    return null
  }
}

function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function useTheme() {
  const theme = ref<Theme>('light')

  /** persist=false 表示这只是「跟随系统」的结果，不应变成用户的显式选择。 */
  function setTheme(next: Theme, persist: boolean) {
    theme.value = next
    document.documentElement.setAttribute('data-theme', next)
    if (persist) {
      try { localStorage.setItem(STORAGE_KEY, next) } catch { /* ignore */ }
    }
    setChartTheme(next)
  }

  function applyTheme(next: Theme) {
    setTheme(next, true)
  }

  function initTheme() {
    const stored = storedTheme()
    setTheme(stored ?? systemTheme(), stored !== null)
    // 未显式选择过主题时，系统在页面打开期间切换深浅色应实时跟进。
    if (!followSystemBound) {
      followSystemBound = true
      window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', event => {
        if (storedTheme() === null) setTheme(event.matches ? 'dark' : 'light', false)
      })
    }
  }

  function toggleTheme() {
    applyTheme(theme.value === 'light' ? 'dark' : 'light')
  }

  return { theme, applyTheme, initTheme, toggleTheme }
}
