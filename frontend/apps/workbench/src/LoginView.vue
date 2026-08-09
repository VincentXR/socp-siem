<script setup lang="ts">
import { ref } from 'vue'
import { login as apiLogin, setToken } from './api'
import { ElMessage } from 'element-plus'

const emit = defineEmits<{ (e: 'done', user: string, role: string): void }>()

const username = ref('demo')
const password = ref('demo123')
const busy = ref(false)

async function doLogin() {
  if (busy.value) return
  busy.value = true
  try {
    const d = await apiLogin(username.value, password.value)
    setToken(d.token)
    try {
      localStorage.setItem('socp_user', d.username)
      localStorage.setItem('socp_role', d.role)
    } catch { /* ignore */ }
    emit('done', d.username, d.role)
  } catch (e) {
    ElMessage.error((e as Error).message || '登录失败')
  } finally {
    busy.value = false
  }
}

function quickFill(u: string, p: string) {
  username.value = u
  password.value = p
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

    <div class="login-card">
      <div class="login-head">
        <h1>登录安全运营中心</h1>
        <p>Security Operations Center</p>
      </div>

      <form class="login-form" @submit.prevent="doLogin">
        <label class="field">
          <span class="field-label">账号</span>
          <input v-model="username" class="input" placeholder="demo / admin" autocomplete="username" />
        </label>
        <label class="field">
          <span class="field-label">密码</span>
          <input v-model="password" type="password" class="input" placeholder="demo123 / admin123" autocomplete="current-password" />
        </label>
        <button type="submit" class="submit" :disabled="busy">
          <span v-if="busy" class="spinner" />
          <span v-else>{{ '登录' }}</span>
        </button>
      </form>

      <div class="quick">
        <span class="quick-label">演示账号</span>
        <button type="button" class="chip" @click="quickFill('demo', 'demo123')">分析师 demo</button>
        <button type="button" class="chip" @click="quickFill('admin', 'admin123')">管理员 admin</button>
      </div>
    </div>

    <div class="login-foot">JWT 会话 30 分钟 · 全链路强制验签 · 多租户隔离</div>
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
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  position: relative;
}
.login-brand {
  display: flex; align-items: center; gap: 10px;
  position: absolute; top: 26px; left: 28px;
}
.brand-mark {
  width: 34px; height: 34px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: var(--ns-accent); color: #fff;
}
.brand-name { font-size: 16px; font-weight: 600; color: var(--ns-text); letter-spacing: .3px; }

.login-card {
  width: 380px;
  padding: 36px 34px 28px;
  border-radius: 24px;
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
  border-radius: 12px;
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
  border: none; border-radius: 999px;
  font-size: 14px; font-weight: 600;
  color: #fff;
  background: var(--ns-accent);
  cursor: pointer;
  transition: background .12s ease;
}
.submit:hover { background: var(--ns-accent-hover); }
.submit:disabled { opacity: .6; cursor: default; }
.spinner {
  display: inline-block; width: 15px; height: 15px;
  border: 2px solid rgba(255, 255, 255, .4); border-top-color: #fff;
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
  font-size: 12px; padding: 5px 13px; border-radius: 999px;
  margin-right: 6px; cursor: pointer;
  transition: background .12s ease, border-color .12s ease, color .12s ease;
}
.chip:hover { background: var(--ns-accent-subtle); border-color: var(--ns-accent); color: var(--ns-accent-fg); }

.login-foot { font-size: 12px; color: var(--ns-text-3); }
</style>
