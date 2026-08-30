import { computed } from 'vue'
import zhCnLocale from 'element-plus/es/locale/lang/zh-cn.mjs'
import enLocale from 'element-plus/es/locale/lang/en.mjs'
import { i18n, SUPPORTED_LOCALES, type SupportedLocale } from './index'

export type { SupportedLocale }

const STORAGE_KEY = 'socp-locale'
const PROFILE_LOCALE_KEYS = ['socp-profile-locale', 'socp_user_locale'] as const

export const locale = computed(() => i18n.global.locale.value as SupportedLocale)
export const elLocale = computed(() => locale.value === 'en-US' ? enLocale : zhCnLocale)

export function getCurrentLocale(): SupportedLocale {
  return locale.value
}

export function normalizeLocale(value: unknown): SupportedLocale | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toLowerCase()
  if (normalized === 'zh' || normalized === 'zh-cn' || normalized === 'zh_cn') return 'zh-CN'
  if (normalized === 'en' || normalized === 'en-us' || normalized === 'en_us') return 'en-US'
  return null
}

function storageValue(key: string): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(key)
  } catch {
    return null
  }
}

function browserLocale(): SupportedLocale | null {
  if (typeof navigator === 'undefined') return null
  const candidates = navigator.languages?.length ? navigator.languages : [navigator.language]
  for (const candidate of candidates) {
    const resolved = normalizeLocale(candidate)
    if (resolved) return resolved
  }
  return null
}

/** Resolve profile preference first, then persisted and browser preferences. */
export function resolveInitialLocale(profileLocale?: unknown): SupportedLocale {
  const profile = normalizeLocale(profileLocale)
    ?? PROFILE_LOCALE_KEYS.map(storageValue).map(normalizeLocale).find(Boolean)
  return profile ?? normalizeLocale(storageValue(STORAGE_KEY)) ?? browserLocale() ?? 'zh-CN'
}

function syncDocument(target: SupportedLocale): void {
  if (typeof document === 'undefined') return
  document.documentElement.lang = target
  const title = i18n.global.t('app.title')
  if (title && title !== 'app.title') document.title = title
}

export function setLocale(target: SupportedLocale, persist = true): void {
  if (!SUPPORTED_LOCALES.includes(target)) return
  i18n.global.locale.value = target
  syncDocument(target)
  if (!persist) return
  try {
    localStorage.setItem(STORAGE_KEY, target)
  } catch {
    // Storage is unavailable in SSR, private browsing, or isolated tests.
  }
}

export function initializeLocale(profileLocale?: unknown): SupportedLocale {
  const target = resolveInitialLocale(profileLocale)
  setLocale(target)
  return target
}

export function toggleLocale(): SupportedLocale {
  const target = locale.value === 'zh-CN' ? 'en-US' : 'zh-CN'
  setLocale(target)
  return target
}
