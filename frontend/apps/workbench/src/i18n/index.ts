import { createI18n } from 'vue-i18n'
import { enUS } from '../locales/en-US.ts'
import { zhCN } from '../locales/zh-CN.ts'

/** The two supported UI locales. Keep this list closed until a message pack is complete. */
export const SUPPORTED_LOCALES = ['zh-CN', 'en-US'] as const
export type SupportedLocale = typeof SUPPORTED_LOCALES[number]

export const messages = {
  'zh-CN': zhCN,
  'en-US': enUS,
} as const

export const datetimeFormats = {
  'zh-CN': {
    short: { year: 'numeric', month: '2-digit', day: '2-digit' },
    dateTime: {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    },
    time: { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false },
  },
  'en-US': {
    short: { year: 'numeric', month: '2-digit', day: '2-digit' },
    dateTime: {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
    },
    time: { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false },
  },
} as const

export const numberFormats = {
  'zh-CN': {
    integer: { maximumFractionDigits: 0 },
    decimal: { minimumFractionDigits: 2, maximumFractionDigits: 2 },
    percent: { style: 'percent', maximumFractionDigits: 1 },
  },
  'en-US': {
    integer: { maximumFractionDigits: 0 },
    decimal: { minimumFractionDigits: 2, maximumFractionDigits: 2 },
    percent: { style: 'percent', maximumFractionDigits: 1 },
  },
} as const

/** Global Vue I18n Composition API instance. */
export const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN' satisfies SupportedLocale,
  fallbackLocale: 'en-US',
  messages,
  datetimeFormats,
  numberFormats,
  missingWarn: import.meta.env?.DEV ?? false,
  fallbackWarn: import.meta.env?.DEV ?? false,
  silentFallbackWarn: !(import.meta.env?.DEV ?? false),
})

export function translate(path: string, params?: Record<string, string | number>): string {
  return i18n.global.t(path, params as Record<string, unknown>)
}

export function formatDate(value: Date | number | string, format = 'dateTime'): string {
  const date = value instanceof Date || typeof value === 'number' ? value : new Date(value)
  return i18n.global.d(date, format)
}

export function formatNumber(value: number, format = 'integer'): string {
  return i18n.global.n(value, format)
}
