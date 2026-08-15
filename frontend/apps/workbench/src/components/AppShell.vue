<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { MENU_ICONS, type MenuGroup } from '../app/navigation'

type Theme = 'light' | 'dark'

defineProps<{
  menuGroups: MenuGroup[]
  activeMenu: string
  activeLabel: string
  theme: Theme
  currentUser: string
  currentRole: string
  userInitials: string
  topSearch: string
}>()

const emit = defineEmits<{
  (event: 'menu-change', key: string): void
  (event: 'toggle-theme'): void
  (event: 'update:top-search', value: string): void
  (event: 'top-search'): void
  (event: 'logout'): void
  (event: 'login'): void
}>()

function onSearchUpdate(value: string | number) {
  emit('update:top-search', String(value))
}
</script>

<template>
  <div class="socp-shell">
    <aside class="socp-sider">
      <div class="socp-logo"><span class="dot" />SOCP 控制台</div>
      <nav class="socp-menu" aria-label="主导航">
        <template v-for="group in menuGroups" :key="group.group">
          <div class="socp-menu-group">{{ group.group }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            type="button"
            :class="['socp-menu-item', { active: activeMenu === item.key }]"
            :aria-current="activeMenu === item.key ? 'page' : undefined"
            :aria-label="item.label"
            :title="item.label"
            @click="emit('menu-change', item.key)"
          >
            <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[item.icon] || ''}</svg>`" />
            <span>{{ item.label }}</span>
          </button>
        </template>
      </nav>
    </aside>

    <div class="socp-main">
      <header class="socp-header">
        <span class="header-crumb">
          <span>控制台</span>
          <span class="header-separator">/</span>
          <span class="header-crumb-cur">{{ activeLabel }}</span>
        </span>
        <span class="header-spacer" />
        <el-input
          :model-value="topSearch"
          placeholder="搜索告警、资产、规则…"
          size="small"
          clearable
          class="header-search"
          @update:model-value="onSearchUpdate"
          @keyup.enter="emit('top-search')"
        />
        <el-button size="small" title="查看告警中心" @click="emit('menu-change', 'alarms')">
          <span class="header-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3a7 7 0 0 0-7 7c0 3-1 5-2.5 6.5h19C20 15 19 13 19 10a7 7 0 0 0-7-7Z"/><path d="M10 20h4"/></svg></span>
        </el-button>
        <el-button size="small" title="切换深色/浅色模式" @click="emit('toggle-theme')">
          <span class="header-icon" aria-hidden="true">
            <svg v-if="theme === 'light'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/></svg>
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/></svg>
          </span>
          {{ theme === 'light' ? '深色' : '浅色' }}
        </el-button>
        <span v-if="currentUser" class="header-user">
          <span class="header-avatar">{{ userInitials }}</span>
          <span class="header-user-name">{{ currentUser }} <span class="mono header-role">{{ currentRole || 'guest' }}</span></span>
          <el-button size="small" @click="emit('logout')">退出</el-button>
        </span>
        <el-button v-else size="small" type="primary" @click="emit('login')">登录</el-button>
      </header>
      <slot />
    </div>
  </div>
</template>
