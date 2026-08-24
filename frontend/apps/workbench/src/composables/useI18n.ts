import { computed, ref, shallowRef } from 'vue';
import { zhCN, type LocaleMessages } from '../locales/zh-CN';
import { enUS } from '../locales/en-US';
import zhCnLocale from 'element-plus/es/locale/lang/zh-cn.mjs';
import enLocale from 'element-plus/es/locale/lang/en.mjs';

export type SupportedLocale = 'zh-CN' | 'en-US';

const STORAGE_KEY = 'socp-locale';

function getInitialLocale(): SupportedLocale {
  try {
    if (typeof window === 'undefined') return 'zh-CN';
    const saved = typeof localStorage !== 'undefined' && localStorage ? localStorage.getItem(STORAGE_KEY) : null;
    if (saved === 'zh-CN' || saved === 'en-US') return saved;
    return 'zh-CN';
  } catch {
    return 'zh-CN';
  }
}

const currentLocale = ref<SupportedLocale>(getInitialLocale());

const messages: Record<SupportedLocale, LocaleMessages> = {
  'zh-CN': zhCN,
  'en-US': enUS,
};

export function useI18n() {
  const locale = computed(() => currentLocale.value);

  const elLocale = computed(() => currentLocale.value === 'en-US' ? enLocale : zhCnLocale);

  function setLocale(target: SupportedLocale) {
    currentLocale.value = target;
    try {
      if (typeof localStorage !== 'undefined' && localStorage) {
        localStorage.setItem(STORAGE_KEY, target);
      }
      if (typeof document !== 'undefined' && document.documentElement) {
        document.documentElement.lang = target === 'en-US' ? 'en' : 'zh-CN';
      }
    } catch {
      // Ignore storage errors in test or sandbox environments
    }
  }

  function toggleLocale() {
    setLocale(currentLocale.value === 'zh-CN' ? 'en-US' : 'zh-CN');
  }

  /**
   * Type-safe key resolver with parameter interpolation.
   * Example: t('overview.title') or t('common.total', { total: 42 })
   */
  function t(path: string, params?: Record<string, string | number>): string {
    const dict = messages[currentLocale.value] || zhCN;
    const segments = path.split('.');
    let value: any = dict;
    for (const segment of segments) {
      if (value && typeof value === 'object' && segment in value) {
        value = value[segment];
      } else {
        value = null;
        break;
      }
    }
    if (typeof value !== 'string') {
      // Fallback to zhCN
      let fallbackVal: any = zhCN;
      for (const segment of segments) {
        if (fallbackVal && typeof fallbackVal === 'object' && segment in fallbackVal) {
          fallbackVal = fallbackVal[segment];
        } else {
          fallbackVal = path;
          break;
        }
      }
      value = typeof fallbackVal === 'string' ? fallbackVal : path;
    }
    if (params) {
      for (const [k, v] of Object.entries(params)) {
        value = value.replace(new RegExp(`\\{${k}\\}`, 'g'), String(v));
      }
    }
    return value;
  }

  return {
    locale,
    elLocale,
    setLocale,
    toggleLocale,
    t,
    isZh: computed(() => currentLocale.value === 'zh-CN'),
    isEn: computed(() => currentLocale.value === 'en-US'),
  };
}
