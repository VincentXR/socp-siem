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
    <div class="orb orb-1" />
    <div class="orb orb-2" />
    <div class="orb orb-3" />
    <div class="grid-overlay" />

    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1" />
            <circle cx="12" cy="12" r="3.2" />
          </svg>
        </div>
        <div>
          <div class="brand-name">SOCP 安全运营中心</div>
          <div class="brand-sub">Security Operations Center</div>
        </div>
      </div>

      <div class="title">欢迎回来</div>
      <div class="subtitle">登录以进入安全运营工作台</div>

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
        <button class="chip" @click="quickFill('demo', 'demo123')">分析师 · demo</button>
        <button class="chip" @click="quickFill('admin', 'admin123')">管理员 · admin</button>
      </div>

      <div class="foot">JWT 会话 30 分钟 · 全链路强制验签 · 多租户隔离</div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1200px 800px at 15% 10%, rgba(99, 91, 255, .18), transparent 55%),
    radial-gradient(1000px 700px at 85% 90%, rgba(0, 212, 255, .14), transparent 55%),
    linear-gradient(160deg, #0b0e1a 0%, #0f1322 45%, #0a0d17 100%);
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.grid-overlay {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, .03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .03) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse at center, black 30%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse at center, black 30%, transparent 75%);
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: .5;
  pointer-events: none;
}
.orb-1 { width: 420px; height: 420px; background: #635bff; top: -120px; left: -80px; }
.orb-2 { width: 360px; height: 360px; background: #00d4ff; bottom: -100px; right: -60px; opacity: .35; }
.orb-3 { width: 260px; height: 260px; background: #7c5cff; top: 55%; left: 60%; opacity: .25; }

.login-card {
  position: relative;
  z-index: 1;
  width: 400px;
  padding: 44px 40px 36px;
  border-radius: 24px;
  background: rgba(255, 255, 255, .055);
  border: 1px solid rgba(255, 255, 255, .12);
  backdrop-filter: blur(28px) saturate(140%);
  -webkit-backdrop-filter: blur(28px) saturate(140%);
  box-shadow: 0 24px 80px rgba(0, 0, 0, .45);
}
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 34px; }
.brand-mark {
  width: 42px; height: 42px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #635bff, #00d4ff);
  color: #fff;
  box-shadow: 0 8px 24px rgba(99, 91, 255, .4);
}
.brand-name { font-size: 14px; font-weight: 600; color: #f2f3f8; letter-spacing: .3px; }
.brand-sub { font-size: 11px; color: rgba(242, 243, 248, .5); margin-top: 2px; }
.title { font-size: 26px; font-weight: 700; color: #f7f8fc; letter-spacing: -.4px; }
.subtitle { font-size: 13px; color: rgba(242, 243, 248, .55); margin-top: 6px; margin-bottom: 28px; }

.field { display: block; margin-bottom: 16px; }
.field-label { display: block; font-size: 12px; color: rgba(242, 243, 248, .65); margin-bottom: 7px; letter-spacing: .2px; }
.input {
  width: 100%; height: 46px;
  padding: 0 16px;
  border-radius: 12px;
  background: rgba(255, 255, 255, .07);
  border: 1px solid rgba(255, 255, 255, .14);
  color: #f2f3f8; font-size: 14px;
  outline: none;
  transition: border-color .2s, box-shadow .2s, background .2s;
}
.input::placeholder { color: rgba(242, 243, 248, .35); }
.input:focus {
  border-color: rgba(99, 91, 255, .8);
  background: rgba(255, 255, 255, .1);
  box-shadow: 0 0 0 4px rgba(99, 91, 255, .18);
}

.submit {
  width: 100%; height: 46px; margin-top: 8px;
  border: none; border-radius: 12px;
  font-size: 14px; font-weight: 600; letter-spacing: .3px;
  color: #fff;
  background: linear-gradient(135deg, #635bff 0%, #4a90ff 100%);
  box-shadow: 0 10px 30px rgba(99, 91, 255, .35);
  cursor: pointer;
  transition: transform .15s, box-shadow .2s, opacity .2s;
}
.submit:hover { transform: translateY(-1px); box-shadow: 0 14px 36px rgba(99, 91, 255, .45); }
.submit:active { transform: translateY(0); }
.submit:disabled { opacity: .6; cursor: default; }
.spinner {
  display: inline-block; width: 16px; height: 16px;
  border: 2px solid rgba(255, 255, 255, .4); border-top-color: #fff;
  border-radius: 50%;
  animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.quick { margin-top: 26px; }
.quick-label { font-size: 11px; color: rgba(242, 243, 248, .4); margin-right: 8px; }
.chip {
  border: 1px solid rgba(255, 255, 255, .16);
  background: rgba(255, 255, 255, .06);
  color: rgba(242, 243, 248, .8);
  font-size: 12px; padding: 5px 12px; border-radius: 999px;
  margin-right: 6px; cursor: pointer;
  transition: background .2s, border-color .2s;
}
.chip:hover { background: rgba(99, 91, 255, .22); border-color: rgba(99, 91, 255, .5); }
.foot { margin-top: 30px; font-size: 11px; color: rgba(242, 243, 248, .32); text-align: center; }
</style>
