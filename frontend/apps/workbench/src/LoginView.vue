<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { authCapabilities, login as apiLogin } from './api'
import { useI18n } from './composables/useI18n'
import { normalizeLocale, setLocale } from './i18n/locale-manager'
import BrandMark from './components/BrandMark.vue'

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
    <section class="login-intro" :aria-label="t('login.eyebrow')">
      <div class="login-brand">
        <BrandMark class="login-brand-mark" />
        <span><strong>SOCP</strong><small>Security Operations</small></span>
      </div>
      <div class="login-intro-content">
        <div class="login-intro-copy">
          <span class="login-eyebrow">SIEM / SOC</span>
          <h1>{{ t('login.heroTitle') }}<span>{{ t('login.heroAccent') }}</span></h1>
          <p>{{ t('login.heroDescription') }}</p>
        </div>
        <div class="login-signal-art" aria-hidden="true">
          <svg class="login-signal-lines" viewBox="0 0 520 224" fill="none">
            <path class="signal-orbit" d="M260 22 415 112 260 202 105 112 260 22Z" />
            <path class="signal-orbit signal-orbit--inner" d="m260 54 100 58-100 58-100-58 100-58Z" />
            <path class="signal-path" d="M22 58h97l44 54h56M22 166h97l44-54M301 112h56l44-54h97M357 112l44 54h97" />
            <path class="signal-path signal-path--accent" d="M119 58l44 54h56M301 112h56l44 54" />
            <g class="signal-terminal"><rect x="12" y="48" width="20" height="20" rx="5"/><rect x="12" y="156" width="20" height="20" rx="5"/><rect x="488" y="48" width="20" height="20" rx="5"/><rect x="488" y="156" width="20" height="20" rx="5"/></g>
            <g class="signal-dot"><circle cx="22" cy="58" r="3"/><circle cx="22" cy="166" r="3"/><circle cx="498" cy="58" r="3"/><circle cx="498" cy="166" r="3"/></g>
          </svg>
          <div class="login-core"><BrandMark class="login-core-mark" /></div>
        </div>
        <div class="login-capabilities">
          <span>{{ t('login.capabilityTriage') }}</span><i aria-hidden="true" />
          <span>{{ t('login.capabilityInvestigate') }}</span><i aria-hidden="true" />
          <span>{{ t('login.capabilityRespond') }}</span>
        </div>
      </div>
      <p class="login-intro-footer">{{ t('login.protectedWorkspace') }}</p>
    </section>

    <section class="login-access">
      <button type="button" class="login-language" @click="toggleLocale"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/></svg>{{ t('login.languageCode') }}</button>
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
        <form v-if="capabilities.localPassword" :aria-describedby="loginError ? 'login-error' : undefined" :aria-busy="busy" class="login-form" @submit.prevent="doLogin">
          <label class="field"><span class="field-label">{{ t('login.username') }}</span><span class="login-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></svg><input v-model="username" name="username" class="input" :placeholder="demoMode ? 'demo / admin' : ''" autocomplete="username" /></span></label>
          <label class="field"><span class="field-label">{{ t('login.password') }}</span><span class="login-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></svg><input v-model="password" name="password" type="password" class="input" :placeholder="demoMode ? 'demo123 / admin123' : ''" autocomplete="current-password" /></span></label>
          <button type="submit" class="submit" :disabled="busy"><span v-if="busy" class="spinner" aria-hidden="true" /><span>{{ busy ? t('login.loggingIn') : t('login.loginBtn') }}</span><svg v-if="!busy" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 12h15m-6-6 6 6-6 6"/></svg></button>
        </form>

        <div v-if="capabilities.oidc" class="oidc-row">
          <div v-if="capabilities.localPassword" class="login-divider"><span>{{ t('login.alternative') }}</span></div>
          <button type="button" class="oidc-btn" :class="{ 'is-primary': !capabilities.localPassword }" @click="oidcLogin"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 7h3m2 0h3M8 11h3m2 0h3M10 21v-6h4v6"/></svg><span>{{ t('login.ssoLogin') }}</span></button>
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
.login-page { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(380px, 1fr); min-height: 100dvh; background: var(--ns-surface); font-family: var(--ns-font-ui); }
.login-intro { position: relative; isolation: isolate; display: flex; flex-direction: column; justify-content: space-between; min-width: 0; overflow: hidden; padding: 42px clamp(32px, 4.4vw, 72px) 30px; color: #eff5ff; background: radial-gradient(ellipse at 72% 54%, #153761 0, transparent 65%), #09172d; }
.login-intro::before { content: ''; position: absolute; inset: 0; z-index: -1; pointer-events: none; background-image: linear-gradient(#789ecc0c 1px, transparent 1px), linear-gradient(90deg, #789ecc0c 1px, transparent 1px); background-size: 52px 52px; mask-image: linear-gradient(180deg, transparent, black 30%, black 70%, transparent); }
.login-brand { display: flex; align-items: center; gap: 12px; }
.login-brand-mark { width: 42px; height: 42px; }
.login-brand strong, .login-brand small { display: block; }
.login-brand strong { font-size: 22px; font-weight: 650; line-height: 1.1; letter-spacing: .1em; }
.login-brand small { margin-top: 5px; color: #a7b9d3; font-size: 12px; letter-spacing: .025em; }
.login-intro-content { width: 100%; max-width: 540px; margin: 64px auto; }
.login-eyebrow { display: inline-block; padding: 5px 9px; border: 1px solid #9ebfe22b; border-radius: 5px; color: #9bb6db; font-family: var(--ns-font-mono); font-size: 12px; letter-spacing: .08em; }
.login-intro-copy h1 { margin: 22px 0 20px; font-size: clamp(32px, 3.3vw, 48px); font-weight: 600; letter-spacing: -.035em; line-height: 1.35; }
.login-intro-copy h1 span { display: block; color: #8ebcf4; }
.login-intro-copy p { max-width: 440px; margin: 0; color: #a7b9d3; font-size: 14px; line-height: 1.9; }
.login-signal-art { position: relative; width: 100%; margin: 32px 0 20px; }
.login-signal-lines { display: block; width: 100%; height: auto; }
.signal-orbit { stroke: #9fc8f329; stroke-width: 1; }
.signal-orbit--inner { stroke: #9fc8f31c; }
.signal-path { stroke: #729bcc55; stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
.signal-path--accent { stroke: #74c6e2; }
.signal-terminal { fill: #112b4c; stroke: #527ca6; }
.signal-dot { fill: #aacdeb; }
.login-core { position: absolute; left: 50%; top: 50%; display: grid; place-items: center; width: 104px; height: 104px; transform: translate(-50%, -50%); border: 1px solid #83b9eb38; border-radius: 25px; background: #17345580; box-shadow: 0 0 48px #5c99e017; }
.login-core-mark { width: 80px; height: 80px; }
.login-capabilities { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 12px; color: #b0c5e0; font-size: 12px; }
.login-capabilities i { width: 3px; height: 3px; border-radius: 50%; background: #607e9f; }
.login-intro-footer { margin: 0; color: #8fa5c3; font-size: 12px; line-height: 1.6; }
.login-access { position: relative; display: flex; align-items: center; justify-content: center; min-width: 0; padding: 100px 40px 72px; }
.login-language { position: absolute; top: 28px; right: 32px; display: inline-flex; align-items: center; gap: 7px; min-height: 34px; padding: 7px 10px; border: 1px solid var(--ns-border); border-radius: 7px; background: var(--ns-surface); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 12px; }
.login-language svg { width: 16px; height: 16px; }
.login-language:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.login-panel { width: 100%; max-width: 380px; }
.login-panel-label { color: var(--ns-accent-fg); font-size: 12px; font-weight: 600; letter-spacing: .04em; }
.login-head h2 { margin: 12px 0 10px; color: var(--ns-text); font-size: 28px; font-weight: 650; letter-spacing: -.025em; line-height: 1.3; }
.login-head p { margin: 0 0 32px; color: var(--ns-text-3); font-size: 13px; line-height: 1.7; }
.field { display: block; margin-bottom: 20px; }
.field-label { display: block; margin-bottom: 8px; color: var(--ns-text-2); font-size: 13px; font-weight: 550; }
.login-input-wrap { position: relative; display: block; }
.login-input-wrap > svg { position: absolute; top: 14px; left: 14px; width: 18px; height: 18px; color: var(--ns-text-3); pointer-events: none; }
.input { width: 100%; height: 46px; padding: 0 14px 0 42px; outline: none; border: 1px solid var(--ns-border-strong); border-radius: 8px; background: var(--ns-input-bg); color: var(--ns-text); font: inherit; font-size: 14px; transition: border-color .15s, box-shadow .15s; }
.input::placeholder { color: var(--ns-text-3); }
.input:focus { border-color: var(--ns-accent); box-shadow: 0 0 0 3px var(--ns-accent-subtle); }
.submit { display: flex; align-items: center; justify-content: center; gap: 14px; width: 100%; height: 46px; margin-top: 6px; border: 1px solid transparent; border-radius: 8px; background: var(--ns-accent); color: var(--ns-on-accent); cursor: pointer; font: inherit; font-size: 14px; font-weight: 600; }
.submit svg { width: 18px; height: 18px; }
.submit:hover { background: var(--ns-accent-hover); }
.submit:disabled { cursor: default; opacity: .64; }
.spinner { width: 15px; height: 15px; border: 2px solid color-mix(in srgb, var(--ns-on-accent) 40%, transparent); border-top-color: var(--ns-on-accent); border-radius: 50%; animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.quick { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 26px; padding: 14px 0; border-top: 1px solid var(--ns-border); border-bottom: 1px solid var(--ns-border); }
.quick-label { margin-right: auto; color: var(--ns-text-3); font-size: 12px; }
.chip { min-height: 30px; padding: 5px 8px; border: 1px solid var(--ns-border); border-radius: 6px; background: var(--ns-surface-muted); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 12px; }
.chip:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.oidc-row { margin-top: 16px; }
.login-divider { display: flex; align-items: center; gap: 12px; margin: 20px 0; color: var(--ns-text-3); font-size: 12px; }
.login-divider::before, .login-divider::after { content: ''; height: 1px; flex: 1; background: var(--ns-border); }
.oidc-btn { display: flex; align-items: center; justify-content: center; gap: 9px; width: 100%; min-height: 46px; padding: 10px 12px; border: 1px solid var(--ns-border-strong); border-radius: 8px; background: var(--ns-surface); color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 13px; }
.oidc-btn svg { width: 18px; height: 18px; flex: none; }
.oidc-btn:hover { border-color: var(--ns-accent); color: var(--ns-accent-fg); }
.oidc-btn.is-primary { background: var(--ns-accent); border-color: var(--ns-accent); color: var(--ns-on-accent); }
.oidc-btn.is-primary:hover { background: var(--ns-accent-hover); }
.login-page button:focus-visible { outline: 2px solid var(--ns-accent); outline-offset: 3px; }
.login-capability-status, .login-capability-error { margin-bottom: 18px; font-size: 13px; line-height: 1.6; }
.login-capability-status { display: flex; align-items: center; gap: 8px; color: var(--ns-text-3); }
.login-loading-bar { width: 24px; height: 2px; overflow: hidden; background: var(--ns-border); }
.login-capability-error { padding: 12px 14px; overflow-wrap: anywhere; border: 1px solid color-mix(in srgb, var(--ns-warning) 28%, var(--ns-border)); border-radius: 8px; background: color-mix(in srgb, var(--ns-warning) 6%, var(--ns-surface)); color: var(--ns-text-2); }
.login-capability-error button { margin: 4px 0 0; padding: 2px 0; border: 0; background: transparent; color: var(--ns-accent-fg); cursor: pointer; font: inherit; text-decoration: underline; text-underline-offset: 3px; }
.login-capability-error button:disabled { cursor: wait; opacity: .6; }
.login-security-note { margin: 24px 0 0; color: var(--ns-text-3); font-size: 12px; line-height: 1.8; }
@media (max-width: 960px) {
  .login-page { grid-template-columns: minmax(0, 1fr) minmax(360px, 1fr); }
  .login-intro { padding: 32px; }
  .login-intro-copy h1 { font-size: 32px; }
  .login-core { width: 80px; height: 80px; border-radius: 21px; }
  .login-core-mark { width: 62px; height: 62px; }
  .login-access { padding-right: 32px; padding-left: 32px; }
}
@media (max-width: 720px) {
  .login-page { position: relative; display: flex; flex-direction: column; }
  .login-intro { flex: none; padding: 26px 24px; background: #09172d; }
  .login-brand-mark { width: 36px; height: 36px; }
  .login-brand strong { font-size: 20px; }
  .login-brand small { font-size: 12px; }
  .login-intro-content, .login-intro-footer { display: none; }
  .login-access { position: static; flex: 1; align-items: flex-start; padding: 40px 24px; }
  .login-language { top: 30px; right: 24px; background: #142743; border-color: #39516f; color: #d2e1f6; }
  .login-language:hover { border-color: #8ebcf4; color: #fff; }
  .login-head h2 { font-size: 25px; }
  .login-head p { margin-bottom: 28px; }
  .quick { gap: 6px; }
  .quick-label { flex-basis: 100%; }
}
</style>
