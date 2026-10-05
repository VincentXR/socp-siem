<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { authCapabilities, login as apiLogin } from './api'
import { useI18n } from './composables/useI18n'
import { normalizeLocale, setLocale } from './i18n/locale-manager'

const emit = defineEmits<{ (e: 'done', user: string, role: string, tenant: string, permissions: string[]): void }>()
const { t, toggleLocale } = useI18n()

const demoMode = import.meta.env.DEV || import.meta.env.VITE_DEMO_MODE === 'true'
const username = ref(demoMode ? 'demo' : '')
const password = ref(demoMode ? 'demo123' : '')
const busy = ref(false)
const loginError = ref('')
const capabilities = ref({ localPassword: demoMode, oidc: false })
const capabilitiesLoading = ref(true)
const capabilitiesError = ref('')

async function loadCapabilities() {
  capabilitiesLoading.value = true
  capabilitiesError.value = ''
  try {
    capabilities.value = await authCapabilities()
  } catch (error) {
    // Discovery failures are diagnostic details, not useful or safe login-page copy.
    console.warn('Authentication capability discovery failed', error)
    capabilities.value = { localPassword: demoMode, oidc: false }
    capabilitiesError.value = t('login.capabilitiesUnavailable')
  } finally {
    capabilitiesLoading.value = false
  }
}

async function doLogin() {
  if (busy.value) return
  busy.value = true
  loginError.value = ''
  try {
    const d = await apiLogin(username.value, password.value)
    const serverLocale = normalizeLocale(d.locale)
    if (serverLocale) setLocale(serverLocale)
    try {
      localStorage.setItem('socp_user', d.username)
      localStorage.setItem('socp_role', d.role)
    } catch { /* optional preference */ }
    emit('done', d.username, d.role, d.tenant, d.permissions ?? [])
  } catch (error) {
    loginError.value = (error as Error).message || t('login.errorInvalid')
  } finally {
    busy.value = false
  }
}

function quickFill(user: string, pass: string) {
  username.value = user
  password.value = pass
}

function oidcLogin() {
  window.location.href = '/auth/oidc/login'
}

onMounted(loadCapabilities)
</script>

<template>
  <main class="login-page">
    <button type="button" class="login-language" @click="toggleLocale">🌐 {{ t('login.languageCode') }}</button>

    <section class="login-stage">
      <div class="login-intro">
        <div class="login-brand">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 32 32" width="24" height="24" fill="none"><path d="M6 9.5 16 4l10 5.5v13L16 28 6 22.5v-13Z" stroke="currentColor" stroke-width="1.7"/><path d="m11 17 3 3 7-8" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
          <span><strong>SOCP</strong><small>SECURITY OPERATIONS</small></span>
        </div>
        <div class="login-intro-copy">
          <span class="login-eyebrow">{{ t('login.eyebrow') }}</span>
          <h1>{{ t('login.heroTitle') }}</h1>
          <p>{{ t('login.heroDescription') }}</p>
        </div>
        <div class="login-capabilities">
          <div><span aria-hidden="true">01</span><strong>{{ t('login.capabilityTriage') }}</strong><small>{{ t('login.capabilityTriageHint') }}</small></div>
          <div><span aria-hidden="true">02</span><strong>{{ t('login.capabilityInvestigate') }}</strong><small>{{ t('login.capabilityInvestigateHint') }}</small></div>
          <div><span aria-hidden="true">03</span><strong>{{ t('login.capabilityRespond') }}</strong><small>{{ t('login.capabilityRespondHint') }}</small></div>
        </div>
        <div class="login-platform-state"><i aria-hidden="true" />{{ t('login.protectedWorkspace') }}</div>
      </div>

      <section class="login-panel" :aria-label="t('login.title')">
        <div class="login-head">
          <span class="login-panel-label">{{ t('login.workspaceAccess') }}</span>
          <h2>{{ t('login.title') }}</h2>
          <p>{{ t('login.subtitle') }}</p>
        </div>

        <div v-if="capabilitiesLoading" class="login-capability-status" role="status">
          <span class="login-loading-bar" />{{ t('login.checkingMethods') }}
        </div>
        <div v-if="capabilitiesError" class="login-capability-error" role="alert">{{ capabilitiesError }} <button type="button" :disabled="capabilitiesLoading" @click="loadCapabilities">{{ t('common.retry') }}</button></div>

        <p v-if="loginError" id="login-error" class="login-capability-error" role="alert">{{ loginError }}</p>
        <form v-if="capabilities.localPassword" :aria-describedby="loginError ? 'login-error' : undefined" class="login-form" @submit.prevent="doLogin">
          <label class="field"><span class="field-label">{{ t('login.username') }}</span><input v-model="username" class="input" :placeholder="demoMode ? 'demo / admin' : ''" autocomplete="username" /></label>
          <label class="field"><span class="field-label">{{ t('login.password') }}</span><input v-model="password" type="password" class="input" :placeholder="demoMode ? 'demo123 / admin123' : ''" autocomplete="current-password" /></label>
          <button type="submit" class="submit" :disabled="busy"><span v-if="busy" class="spinner" /><span>{{ busy ? t('login.loggingIn') : t('login.loginBtn') }}</span></button>
        </form>

        <div v-if="capabilities.oidc" class="oidc-row">
          <button type="button" class="oidc-btn" @click="oidcLogin"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="11" width="18" height="10" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg><span>Keycloak {{ t('login.ssoLogin') }}</span></button>
        </div>

        <p v-if="!capabilitiesLoading && !capabilities.localPassword && !capabilities.oidc" class="login-capability-error">{{ t('login.noMethods') }}</p>

        <div v-if="demoMode && capabilities.localPassword" class="quick">
          <span class="quick-label">{{ t('login.demoAccounts') }}</span>
          <button type="button" class="chip" @click="quickFill('demo', 'demo123')">{{ t('login.analystDemo') }}</button>
          <button type="button" class="chip" @click="quickFill('admin', 'admin123')">{{ t('login.adminDemo') }}</button>
        </div>
        <p class="login-security-note">{{ t('login.securityHint') }}</p>
      </section>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100dvh; display: grid; place-items: center; padding: 48px; overflow: hidden; position: relative; background: var(--ns-bg-subtle); font-family: var(--ns-font-ui); }
.login-page::before { content: ''; position: absolute; inset: 0; pointer-events: none; background-image: linear-gradient(var(--ns-border) 1px, transparent 1px), linear-gradient(90deg, var(--ns-border) 1px, transparent 1px); background-size: 48px 48px; opacity: .24; mask-image: linear-gradient(to bottom right, black, transparent 72%); }
.login-language { position: absolute; z-index: 2; top: 24px; right: 28px; padding: 7px 11px; border: 1px solid var(--ns-border); border-radius: 7px; background: color-mix(in srgb, var(--ns-surface) 88%, transparent); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 12px; }
.login-language:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.login-stage { position: relative; z-index: 1; display: grid; grid-template-columns: minmax(420px, 1.2fr) minmax(360px, .8fr); width: min(1020px, 100%); min-height: 610px; overflow: hidden; border: 1px solid var(--ns-border); border-radius: 16px; background: var(--ns-surface); box-shadow: 0 24px 70px color-mix(in srgb, #0b1631 16%, transparent); }
.login-intro { display: flex; flex-direction: column; padding: 46px 52px 40px; color: #ecf4ff; background: radial-gradient(circle at 16% 10%, rgba(61, 132, 255, .27), transparent 34%), linear-gradient(145deg, #0f2448 0%, #0a1730 62%, #081326 100%); }
.login-brand { display: flex; align-items: center; gap: 11px; }
.brand-mark { display: grid; width: 40px; height: 40px; place-items: center; border: 1px solid rgba(164, 201, 255, .3); border-radius: 10px; background: rgba(61, 132, 255, .15); color: #8ab8ff; }
.login-brand strong, .login-brand small { display: block; }
.login-brand strong { font-size: 16px; letter-spacing: .08em; }
.login-brand small { margin-top: 2px; color: rgba(218, 233, 255, .55); font-size: 9px; letter-spacing: .18em; }
.login-intro-copy { max-width: 520px; margin: auto 0 36px; }
.login-eyebrow, .login-panel-label { color: #76aaff; font-size: 12px; font-weight: 700; letter-spacing: .12em; text-transform: uppercase; }
.login-intro-copy h1 { margin: 12px 0 14px; font-size: clamp(34px, 4vw, 50px); font-weight: 620; letter-spacing: -.045em; line-height: 1.08; }
.login-intro-copy p { max-width: 470px; margin: 0; color: rgba(222, 235, 255, .66); font-size: 14px; line-height: 1.7; }
.login-capabilities { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.login-capabilities > div { min-width: 0; padding: 13px; border: 1px solid rgba(164, 201, 255, .13); border-radius: 9px; background: rgba(255, 255, 255, .035); }
.login-capabilities span, .login-capabilities strong, .login-capabilities small { display: block; }
.login-capabilities span { margin-bottom: 12px; color: #76aaff; font-family: var(--ns-font-mono); font-size: 12px; }
.login-capabilities strong { font-size: 12px; }
.login-capabilities small { margin-top: 4px; color: rgba(222, 235, 255, .52); font-size: 12px; line-height: 1.45; }
.login-platform-state { display: flex; align-items: center; gap: 8px; margin-top: 24px; color: rgba(222, 235, 255, .54); font-size: 12px; letter-spacing: .04em; }
.login-platform-state i { width: 6px; height: 6px; border-radius: 50%; background: #54d29c; box-shadow: 0 0 0 4px rgba(84, 210, 156, .12); }
.login-panel { align-self: center; padding: 48px 44px; }
.login-panel-label { color: var(--ns-accent-fg); }
.login-head h2 { margin: 10px 0 6px; color: var(--ns-text); font-size: 24px; font-weight: 650; letter-spacing: -.025em; }
.login-head p { margin: 0 0 28px; color: var(--ns-text-3); font-size: 12px; line-height: 1.55; }
.field { display: block; margin-bottom: 15px; }
.field-label { display: block; margin-bottom: 7px; color: var(--ns-text-2); font-size: 12px; font-weight: 550; }
.input { width: 100%; height: 44px; padding: 0 13px; outline: none; border: 1px solid var(--ns-border-strong); border-radius: 7px; background: var(--ns-input-bg); color: var(--ns-text); font: inherit; font-size: 14px; transition: border-color .15s, box-shadow .15s; }
.input::placeholder { color: var(--ns-text-3); }
.input:focus { border-color: var(--ns-accent); box-shadow: 0 0 0 3px var(--ns-accent-subtle); }
.submit { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; height: 44px; margin-top: 8px; border: 0; border-radius: 7px; background: var(--ns-accent); color: var(--ns-on-accent); cursor: pointer; font: inherit; font-size: 13px; font-weight: 650; }
.submit:hover { background: var(--ns-accent-hover); }
.submit:disabled { cursor: default; opacity: .64; }
.spinner { width: 15px; height: 15px; border: 2px solid color-mix(in srgb, var(--ns-on-accent) 40%, transparent); border-top-color: var(--ns-on-accent); border-radius: 50%; animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.quick { margin-top: 22px; padding-top: 16px; border-top: 1px solid var(--ns-border); }
.quick-label { margin-right: 8px; color: var(--ns-text-3); font-size: 12px; }
.chip { margin: 4px 4px 0 0; padding: 5px 9px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-bg-subtle); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 12px; }
.chip:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.oidc-row { margin-top: 12px; }
.oidc-btn { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; height: 42px; border: 1px solid var(--ns-border-strong); border-radius: 7px; background: var(--ns-bg-subtle); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 12px; }
.oidc-btn:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.login-capability-status, .login-capability-error { margin-bottom: 14px; font-size: 12px; }
.login-capability-status { display: flex; align-items: center; gap: 8px; color: var(--ns-text-3); }
.login-loading-bar { width: 24px; height: 2px; overflow: hidden; background: var(--ns-border); }
.login-capability-error { padding: 9px 10px; border: 1px solid color-mix(in srgb, var(--ns-warning) 28%, var(--ns-border)); border-radius: 6px; background: color-mix(in srgb, var(--ns-warning) 6%, var(--ns-surface)); color: var(--ns-text-2); }
.login-security-note { margin: 22px 0 0; color: var(--ns-text-3); font-size: 12px; text-align: center; }
@media (max-width: 820px) {
  .login-page { align-items: start; padding: 72px 18px 30px; overflow: auto; }
  .login-stage { grid-template-columns: 1fr; min-height: auto; }
  .login-panel { grid-row: 1; }
  .login-intro { padding: 30px; }
  .login-intro-copy { margin: 48px 0 26px; }
  .login-intro-copy h1 { font-size: 32px; }
  .login-panel { padding: 34px 30px; }
}
@media (max-width: 520px) {
  .login-page { padding-right: 12px; padding-left: 12px; }
  .login-stage { border-radius: 12px; }
  .login-capabilities { grid-template-columns: 1fr; }
  .login-capabilities > div { display: none; }
  .login-capabilities > div:first-child { display: block; }
  .login-intro, .login-panel { padding: 26px 22px; }
}
</style>
