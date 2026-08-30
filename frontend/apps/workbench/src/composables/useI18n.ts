import { computed } from 'vue'
import { formatDate, formatNumber, translate } from '../i18n'
import {
  elLocale,
  locale,
  setLocale,
  toggleLocale,
  type SupportedLocale,
} from '../i18n/locale-manager'

/**
 * Transitional facade for existing components. The implementation is now
 * Vue I18n's global Composition API composer; callers can migrate from this
 * facade to `useI18n({ useScope: 'global' })` incrementally.
 */
export function useI18n() {
  return {
    locale,
    elLocale,
    setLocale,
    toggleLocale,
    t: translate,
    d: formatDate,
    n: formatNumber,
    isZh: computed(() => locale.value === 'zh-CN'),
    isEn: computed(() => locale.value === 'en-US'),
  }
}

export type { SupportedLocale }
