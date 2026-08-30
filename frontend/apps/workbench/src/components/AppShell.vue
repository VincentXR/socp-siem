<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { MENU_ICONS, type MenuGroup } from '../app/navigation'
import { useI18n } from '../composables/useI18n'

type Theme = 'light' | 'dark'

defineProps<{
  menuGroups: MenuGroup[]
  activeMenu: string
  activeLabel: string
  theme: Theme
  currentUser: string
  currentRole: string
  userInitials: string
}>()

const emit = defineEmits<{
  (event: 'menu-change', key: string): void
  (event: 'toggle-theme'): void
  (event: 'logout'): void
}>()

const { t, toggleLocale } = useI18n()

</script>

<template>
  <div class="socp-shell">
    <aside class="socp-sider">
      <div class="socp-logo"><span class="dot" />{{ t('app.title') }}</div>
      <nav class="socp-menu" :aria-label="t('app.console')">
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
            :data-menu-key="item.key"
            @click="emit('menu-change', item.key)"
          >
            <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[item.icon] || ''}</svg>`" />
            <span>{{ item.label }}</span>
          </button>
        </template>
      </nav>
      <div class="socp-sidebar-footer" aria-label="Platform status">
        <span class="sidebar-status-dot" aria-hidden="true" />
        <span>{{ t('app.platformStatus') }}</span>
        <span class="sidebar-version mono">{{ t('app.version') }}</span>
      </div>
    </aside>

    <div class="socp-main">
      <header class="socp-header">
        <span class="header-crumb">
          <span>{{ t('app.console') }}</span>
          <span class="header-separator">/</span>
          <span class="header-crumb-cur">{{ activeLabel }}</span>
        </span>
        <span class="header-spacer" />
        <el-button size="small" :title="t('app.langToggle')" @click="toggleLocale">
          <span class="header-icon" aria-hidden="true">🌐</span>
          {{ t('inline.appShell.message') }}
        </el-button>
        <el-button size="small" :title="t('app.themeToggle')" @click="emit('toggle-theme')">
          <span class="header-icon" aria-hidden="true">
            <svg v-if="theme === 'light'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/></svg>
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/></svg>
          </span>
          {{ theme === 'light' ? t('app.themeDark') : t('app.themeLight') }}
        </el-button>
        <span v-if="currentUser" class="header-user">
          <span class="header-avatar">{{ userInitials }}</span>
          <span class="header-user-name">{{ currentUser }} <span class="mono header-role">{{ currentRole || t('app.guest') }}</span></span>
          <el-button size="small" @click="emit('logout')">{{ t('app.logout') }}</el-button>
        </span>
      </header>
      <slot />
    </div>
  </div>
</template>
