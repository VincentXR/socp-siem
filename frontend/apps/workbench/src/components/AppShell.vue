<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { MENU_ICONS, type MenuGroup } from '../app/navigation'
import CommandPalette from './CommandPalette.vue'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { useRoute } from 'vue-router'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

type Theme = 'light' | 'dark'

const props = defineProps<{
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

const route = useRoute()

/** Editor and detail routes carry `meta.crumbKey`; the label stays empty when no message defines it. */
const subCrumb = computed(() => {
  const key = route.meta.crumbKey
  return typeof key === 'string' ? tOr(t, key, '') : ''
})

function goOverview(): void {
  mobileSidebarOpen.value = false
  emit('menu-change', 'overview')
}

const collapsedGroups = ref<Record<string, boolean>>({})
const recentMenuKeys = ref<string[]>([])
const paletteOpen = ref(false)
const mobileSidebarOpen = ref(false)

/**
 * Global quick-jump hotkey. It deliberately fires while a field has focus, so
 * the browser's native search-field shortcut is suppressed instead. SOAR's
 * editor owns Ctrl+S and the canvas shortcuts, never Ctrl+K.
 */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && mobileSidebarOpen.value) {
    mobileSidebarOpen.value = false
    return
  }
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return
  if (event.key.toLowerCase() !== 'k') return
  event.preventDefault()
  paletteOpen.value = true
}

function jumpTo(key: string): void {
  paletteOpen.value = false
  mobileSidebarOpen.value = false
  emit('menu-change', key)
}

const isMobileViewport = () => typeof window !== 'undefined' && Boolean(window.matchMedia?.('(max-width: 860px)').matches)
const mobileViewport = ref(isMobileViewport())
const COLLAPSED_GROUPS_KEY = 'socp.sidebar.collapsed-groups'
const RECENT_MENU_KEY = 'socp.sidebar.recent-menus'

function groupKey(group: MenuGroup): string {
  return group.items.map(item => item.key).join(',')
}

try {
  const stored = localStorage.getItem(COLLAPSED_GROUPS_KEY)
  if (stored) collapsedGroups.value = JSON.parse(stored) as Record<string, boolean>
  const recent = localStorage.getItem(RECENT_MENU_KEY)
  if (recent) recentMenuKeys.value = JSON.parse(recent) as string[]
} catch { /* keep the expanded defaults */ }

const recentItems = computed(() => {
  const items = props.menuGroups.flatMap(group => group.items)
  return recentMenuKeys.value
    .map(key => items.find(item => item.key === key))
    .filter((item): item is MenuGroup['items'][number] => Boolean(item))
})

function isGroupCollapsed(group: MenuGroup): boolean {
  const key = groupKey(group)
  return Object.prototype.hasOwnProperty.call(collapsedGroups.value, key)
    ? collapsedGroups.value[key] === true
    : group.defaultCollapsed === true
}

function updateSidebarMode(): void {
  mobileViewport.value = isMobileViewport()
  if (!mobileViewport.value) mobileSidebarOpen.value = false
}

function selectMenu(key: string): void {
  mobileSidebarOpen.value = false
  emit('menu-change', key)
}

function toggleGroup(group: MenuGroup): void {
  const key = groupKey(group)
  collapsedGroups.value[key] = !isGroupCollapsed(group)
  try { localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify(collapsedGroups.value)) } catch { /* optional preference */ }
}

watch(() => props.activeMenu, activeMenu => {
  mobileSidebarOpen.value = false
  recentMenuKeys.value = [activeMenu, ...recentMenuKeys.value.filter(key => key !== activeMenu)].slice(0, 3)
  try { localStorage.setItem(RECENT_MENU_KEY, JSON.stringify(recentMenuKeys.value)) } catch { /* optional preference */ }
  const activeGroup = props.menuGroups.find(group => group.items.some(item => item.key === activeMenu))
  if (activeGroup && isGroupCollapsed(activeGroup)) {
    collapsedGroups.value[groupKey(activeGroup)] = false
    try { localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify(collapsedGroups.value)) } catch { /* optional preference */ }
  }
}, { immediate: true })

onMounted(() => {
  window.addEventListener('resize', updateSidebarMode)
  document.addEventListener('keydown', onGlobalKeydown)
})
onUnmounted(() => {
  window.removeEventListener('resize', updateSidebarMode)
  document.removeEventListener('keydown', onGlobalKeydown)
})

</script>

<template>
  <div class="socp-shell">
    <aside id="socp-primary-navigation" class="socp-sider" :class="{ 'is-mobile-open': mobileSidebarOpen }" :aria-hidden="mobileViewport && !mobileSidebarOpen" :inert="mobileViewport && !mobileSidebarOpen ? true : undefined">
      <div class="socp-sidebar-brand">
        <button type="button" class="socp-logo" :title="t('menu.overview')" :aria-label="t('menu.overview')" @click="goOverview"><span class="dot" />{{ t('app.title') }}</button>
        <button type="button" class="socp-sidebar-close" :aria-label="t('nav.closeNavigation')" @click="mobileSidebarOpen = false">×</button>
      </div>
      <div v-if="recentItems.length > 0" class="socp-recent-nav">
        <div class="socp-recent-label">{{ t('common.recent') }}</div>
        <button
          v-for="item in recentItems"
          :key="`recent-${item.key}`"
          type="button"
          class="socp-recent-item"
          :class="{ active: activeMenu === item.key }"
          :title="item.label"
          @click="selectMenu(item.key)"
        >
          <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[item.icon] || ''}</svg>`" />
          <span>{{ item.label }}</span>
        </button>
      </div>
      <nav class="socp-menu" :aria-label="t('app.console')">
        <template v-for="group in menuGroups" :key="group.group">
          <button type="button" class="socp-menu-group" :class="{ 'is-secondary': group.secondary }" :aria-expanded="!isGroupCollapsed(group)" @click="toggleGroup(group)">
            <span>{{ group.group }}</span>
            <span class="socp-menu-group-toggle" aria-hidden="true">{{ isGroupCollapsed(group) ? '+' : '−' }}</span>
          </button>
          <div v-show="!isGroupCollapsed(group)" class="socp-menu-items">
            <button
              v-for="item in group.items"
              :key="item.key"
              type="button"
              :class="['socp-menu-item', { active: activeMenu === item.key }]"
              :aria-current="activeMenu === item.key ? 'page' : undefined"
              :aria-label="item.label"
              :title="item.label"
              :data-menu-key="item.key"
              @click="selectMenu(item.key)"
            >
              <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[item.icon] || ''}</svg>`" />
              <span>{{ item.label }}</span>
            </button>
          </div>
        </template>
      </nav>
      <div class="socp-sidebar-footer" :aria-label="t('app.platformStatus')">
        <span class="sidebar-status-dot" aria-hidden="true" />
        <span>{{ t('app.platformStatus') }}</span>
        <span class="sidebar-version mono">{{ t('app.version') }}</span>
      </div>
    </aside>
    <button v-if="mobileViewport && mobileSidebarOpen" type="button" class="socp-sidebar-backdrop" :aria-label="t('nav.closeNavigation')" @click="mobileSidebarOpen = false" />

    <div class="socp-main">
      <header class="socp-header">
        <button
          type="button"
          class="socp-mobile-menu"
          :aria-label="mobileSidebarOpen ? t('nav.closeNavigation') : t('nav.openNavigation')"
          :aria-expanded="mobileSidebarOpen"
          aria-controls="socp-primary-navigation"
          @click="mobileSidebarOpen = !mobileSidebarOpen"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
        </button>
        <span class="header-crumb">
          <button type="button" class="header-crumb-link" :title="t('menu.overview')" @click="goOverview">{{ t('app.console') }}</button>
          <span class="header-separator">/</span>
          <button v-if="subCrumb" type="button" class="header-crumb-link" :title="activeLabel" @click="emit('menu-change', activeMenu)">{{ activeLabel }}</button>
          <span v-else class="header-crumb-cur">{{ activeLabel }}</span>
          <template v-if="subCrumb">
            <span class="header-separator">/</span>
            <span class="header-crumb-cur">{{ subCrumb }}</span>
          </template>
        </span>
        <span class="header-spacer" />
        <el-button size="small" :title="t('nav.commandPalette')" :aria-label="t('nav.commandPalette')" @click="paletteOpen = true">
          <span class="header-icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' width='14' height='14' fill='none' stroke='currentColor' stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS.search}</svg>`" />
        </el-button>
        <el-button class="header-action" size="small" :title="t('app.langToggle')" @click="toggleLocale">
          <span class="header-icon" aria-hidden="true">🌐</span>
          <span class="header-action-label">{{ t('app.languageCode') }}</span>
        </el-button>
        <el-button class="header-action" size="small" :title="t('app.themeToggle')" @click="emit('toggle-theme')">
          <span class="header-icon" aria-hidden="true">
            <svg v-if="theme === 'light'" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8Z"/></svg>
            <svg v-else viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4"/></svg>
          </span>
          <span class="header-action-label">{{ theme === 'light' ? t('app.themeDark') : t('app.themeLight') }}</span>
        </el-button>
        <span v-if="currentUser" class="header-user">
          <span class="header-avatar">{{ userInitials }}</span>
          <span class="header-user-name">{{ currentUser }} <span class="mono header-role">{{ currentRole || t('app.guest') }}</span></span>
          <el-button class="header-logout" size="small" :title="t('app.logout')" @click="emit('logout')"><span class="header-action-label">{{ t('app.logout') }}</span><span class="header-logout-icon" aria-hidden="true">↗</span></el-button>
        </span>
      </header>
      <slot />
      <CommandPalette v-model="paletteOpen" :menu-groups="menuGroups" @select="jumpTo" />
    </div>
  </div>
</template>

<style scoped>
/* Navigation affordances are real buttons, so neutralise the UA button chrome
   without touching the layout declarations the global sheet owns. The logo's
   border-bottom is deliberately left to styles.css. */
button.socp-logo {
  margin: 0;
  appearance: none;
  border-top: 0;
  border-right: 0;
  border-left: 0;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
}
.header-crumb-link {
  margin: 0;
  padding: 0;
  appearance: none;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.header-crumb-link:hover { color: var(--ns-accent-fg); }
.header-logout-icon { display: none; }
@media (max-width: 860px) {
  .socp-sider {
    position: fixed;
    inset: 0 auto 0 0;
    width: min(290px, calc(100vw - 44px));
    transform: translateX(-101%);
    transition: transform var(--ns-motion-base) ease;
    box-shadow: 18px 0 44px color-mix(in srgb, #071329 22%, transparent);
  }
  .socp-sider.is-mobile-open { transform: translateX(0); }
  .socp-logo { justify-content: flex-start; padding: 0 20px; font-size: 14px; }
  .socp-menu { padding: 12px; }
  .socp-menu-group { display: flex; justify-content: space-between; padding: 12px 10px 6px; font-size: 10px; }
  .socp-menu-group-toggle { display: inline; }
  .socp-menu-item { justify-content: flex-start; width: 100%; margin: 2px 0; padding: 0 12px; }
  .socp-menu-item span:not(.icon) { display: inline; }
  .socp-sidebar-backdrop {
    position: fixed;
    z-index: 15;
    inset: 0;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: color-mix(in srgb, #071329 46%, transparent);
    cursor: default;
  }
  .socp-sidebar-close { display: inline-flex; width: 34px; height: 34px; margin-right: 10px; border-radius: 7px; font-size: 20px; }
  .socp-sidebar-close:hover { background: var(--ns-surface-muted); }
  .socp-mobile-menu { display: inline-flex; width: 32px; height: 32px; flex: none; border: 1px solid var(--ns-border); border-radius: 7px; background: var(--ns-surface); }
  .socp-main { width: 100%; }
  .socp-header { padding: 0 16px; }
  .header-user-name { display: none; }
}
@media (max-width: 720px) {
  .socp-header { gap: 6px; }
  .socp-header .header-crumb { overflow: hidden; }
  .header-crumb-link, .header-crumb-cur { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .header-action-label { display: none; }
  .header-action, .header-logout { width: 32px; padding-right: 0; padding-left: 0; }
  .header-logout-icon { display: inline; }
  .header-user { gap: 5px; padding-left: 0; border-left: 0; }
}
@media (max-width: 520px) {
  .socp-header .header-crumb-link:first-child, .socp-header .header-separator:first-of-type { display: none; }
  .header-avatar { display: none; }
}
</style>
