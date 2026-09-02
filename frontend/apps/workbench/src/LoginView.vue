<script setup lang="ts">
import { ref } from 'vue'
import { login as apiLogin } from './api'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { useI18n } from './composables/useI18n'
import { normalizeLocale, setLocale } from './i18n/locale-manager'

const emit = defineEmits<{ (e: 'done', user: string, role: string): void }>()
const { t, toggleLocale } = useI18n()

const demoMode = import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true'
const username = ref(demoMode ? 'demo' : '')
const password = ref(demoMode ? 'demo123' : '')
const busy = ref(false)

async function doLogin() {
  if (busy.value) return
  busy.value = true
  try {
    const d = await apiLogin(username.value, password.value)
    const serverLocale = normalizeLocale(d.locale)
    if (serverLocale) setLocale(serverLocale)
    try {
      localStorage.setItem('socp_user', d.username)
      localStorage.setItem('socp_role', d.role)
    } catch { /* ignore */ }
    emit('done', d.username, d.role)
  } catch (e) {
    ElMessage.error((e as Error).message || t('login.errorInvalid'))
  } finally {
    busy.value = false
  }
}

function quickFill(u: string, p: string) {
  username.value = u
  password.value = p
}

/** Keycloak OIDC login returns with an HttpOnly SOCP session cookie. */
function oidcLogin() {
  window.location.href = '/auth/oidc/login'
}
</script>

<template>
  <div class="login-page">
    <div class="login-brand">
      <div class="brand-mark">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
          <circle cx="12" cy="12" r="3.2" />
        </svg>
      </div>
      <span class="brand-name">SOCP</span>
    </div>

    <div class="login-lang-switch">
      <button type="button" class="lang-btn" @click="toggleLocale">
        🌐 {{ t('login.languageCode') }}
      </button>
    </div>

    <div class="login-card">
      <div class="login-head">
        <h1>{{ t('login.title') }}</h1>
        <p>{{ t('login.subtitle') }}</p>
      </div>

      <form class="login-form" @submit.prevent="doLogin">
        <label class="field">
          <span class="field-label">{{ t('login.username') }}</span>
          <input v-model="username" class="input" :placeholder="demoMode ? 'demo / admin' : ''" autocomplete="username" />
        </label>
        <label class="field">
          <span class="field-label">{{ t('login.password') }}</span>
          <input v-model="password" type="password" class="input" :placeholder="demoMode ? 'demo123 / admin123' : ''" autocomplete="current-password" />
        </label>
        <button type="submit" class="submit" :disabled="busy">
          <span v-if="busy" class="spinner" />
          <span v-else>{{ busy ? t('login.loggingIn') : t('login.loginBtn') }}</span>
        </button>
      </form>

      <div v-if="demoMode" class="quick">
        <span class="quick-label">{{ t('login.demoAccounts') }}</span>
        <button type="button" class="chip" @click="quickFill('demo', 'demo123')">{{ t('login.analystDemo') }}</button>
        <button type="button" class="chip" @click="quickFill('admin', 'admin123')">{{ t('login.adminDemo') }}</button>
      </div>

      <div class="oidc-row">
        <button type="button" class="oidc-btn" @click="oidcLogin">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span>Keycloak {{ t('login.ssoLogin') }}</span>
        </button>
      </div>
    </div>

    <div class="login-foot">JWT {{ t('login.securityHint') }}</div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  background: var(--ns-bg-subtle);
  font-family: var(--ns-font-ui);
  position: relative;
}
.login-brand {
  display: flex; align-items: center; gap: 10px;
  position: absolute; top: 26px; left: 28px;
}
.login-lang-switch {
  position: absolute; top: 26px; right: 28px;
}
.lang-btn {
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid var(--ns-border);
  background: var(--ns-surface);
  color: var(--ns-text);
  font-size: 13px;
  cursor: pointer;
  transition: all .15s ease;
}
.lang-btn:hover {
  border-color: var(--ns-accent);
  color: var(--ns-accent);
}
.brand-mark {
  width: 34px; height: 34px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  background: var(--ns-accent); color: var(--ns-on-accent);
}
.brand-name { font-size: 16px; font-weight: 600; color: var(--ns-text); letter-spacing: .3px; }

.login-card {
  width: 380px;
  padding: 36px 34px 28px;
  border-radius: 12px;
  background: var(--ns-surface);
  border: 1px solid var(--ns-border);
  box-shadow: var(--ns-shadow-lg);
}
.login-head h1 { margin: 0; font-size: 20px; font-weight: 600; color: var(--ns-text); letter-spacing: -.3px; }
.login-head p { margin: 4px 0 24px; font-size: 12.5px; color: var(--ns-text-3); }

.field { display: block; margin-bottom: 14px; }
.field-label { display: block; font-size: 12.5px; color: var(--ns-text-2); margin-bottom: 6px; }
.input {
  width: 100%; height: 44px;
  padding: 0 14px;
  border-radius: 7px;
  background: var(--ns-input-bg);
  border: 1px solid var(--ns-border-strong);
  color: var(--ns-text); font-size: 14px;
  outline: none;
  transition: border-color .15s, box-shadow .15s;
}
.input::placeholder { color: var(--ns-text-3); }
.input:focus {
  border-color: var(--ns-accent);
  box-shadow: 0 0 0 3px var(--ns-accent-subtle);
}

.submit {
  width: 100%; height: 44px; margin-top: 6px;
  border: none; border-radius: 7px;
  font-size: 14px; font-weight: 600;
  color: var(--ns-on-accent);
  background: var(--ns-accent);
  cursor: pointer;
  transition: background .12s ease;
}
.submit:hover { background: var(--ns-accent-hover); }
.submit:disabled { opacity: .6; cursor: default; }
.spinner {
  display: inline-block; width: 15px; height: 15px;
  border: 2px solid color-mix(in srgb, var(--ns-on-accent) 40%, transparent); border-top-color: var(--ns-on-accent);
  border-radius: 50%;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.quick { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--ns-border); }
.quick-label { font-size: 12px; color: var(--ns-text-3); margin-right: 8px; }
.chip {
  border: 1px solid var(--ns-border-strong);
  background: var(--ns-bg-subtle);
  color: var(--ns-text-2);
  font-size: 12px; padding: 5px 11px; border-radius: 6px;
  margin-right: 6px; cursor: pointer;
  transition: background .12s ease, border-color .12s ease, color .12s ease;
}
.chip:hover { background: var(--ns-accent-subtle); border-color: var(--ns-accent); color: var(--ns-accent-fg); }

.oidc-row { margin-top: 14px; }
.oidc-btn {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; height: 42px;
  border: 1px solid var(--ns-border-strong);
  border-radius: 7px;
  background: var(--ns-bg-subtle);
  color: var(--ns-text-2);
  font-size: 13px; font-weight: 500;
  cursor: pointer;
  transition: background .12s ease, border-color .12s ease, color .12s ease;
}
.oidc-btn:hover { background: var(--ns-accent-subtle); border-color: var(--ns-accent); color: var(--ns-accent-fg); }

.login-foot { font-size: 12px; color: var(--ns-text-3); }
</style>
