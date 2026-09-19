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
  emit('menu-change', 'overview')
}

const collapsedGroups = ref<Record<string, boolean>>({})
const recentMenuKeys = ref<string[]>([])
const paletteOpen = ref(false)

/**
 * Global quick-jump hotkey. It deliberately fires while a field has focus, so
 * the browser's native search-field shortcut is suppressed instead. SOAR's
 * editor owns Ctrl+S and the canvas shortcuts, never Ctrl+K.
 */
function onGlobalKeydown(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return
  if (event.key.toLowerCase() !== 'k') return
  event.preventDefault()
  paletteOpen.value = true
}

function jumpTo(key: string): void {
  paletteOpen.value = false
  emit('menu-change', key)
}

const isCompactViewport = () => typeof window !== 'undefined' && Boolean(window.matchMedia?.('(max-width: 640px)').matches)
const compactSidebar = ref(isCompactViewport())
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
  // The compact sidebar hides group headers, so a desktop collapse preference
  // must not make icon-only routes unreachable on phones.
  return !compactSidebar.value && collapsedGroups.value[groupKey(group)] === true
}

function updateSidebarMode(): void {
  compactSidebar.value = isCompactViewport()
}

function toggleGroup(group: MenuGroup): void {
  const key = groupKey(group)
  collapsedGroups.value[key] = !isGroupCollapsed(group)
  try { localStorage.setItem(COLLAPSED_GROUPS_KEY, JSON.stringify(collapsedGroups.value)) } catch { /* optional preference */ }
}

watch(() => props.activeMenu, activeMenu => {
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
    <aside class="socp-sider">
      <button type="button" class="socp-logo" :title="t('menu.overview')" :aria-label="t('menu.overview')" @click="goOverview"><span class="dot" />{{ t('app.title') }}</button>
      <div v-if="recentItems.length > 0" class="socp-recent-nav">
        <div class="socp-recent-label">{{ t('common.recent') }}</div>
        <button
          v-for="item in recentItems"
          :key="`recent-${item.key}`"
          type="button"
          class="socp-recent-item"
          :class="{ active: activeMenu === item.key }"
          :title="item.label"
          @click="emit('menu-change', item.key)"
        >
          <span class="icon" aria-hidden="true" v-html="`<svg viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.6' stroke-linecap='round' stroke-linejoin='round'>${MENU_ICONS[item.icon] || ''}</svg>`" />
          <span>{{ item.label }}</span>
        </button>
      </div>
      <nav class="socp-menu" :aria-label="t('app.console')">
        <template v-for="group in menuGroups" :key="group.group">
          <button type="button" class="socp-menu-group" :aria-expanded="!isGroupCollapsed(group)" @click="toggleGroup(group)">
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
              @click="emit('menu-change', item.key)"
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

    <div class="socp-main">
      <header class="socp-header">
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
        <el-button size="small" :title="t('app.langToggle')" @click="toggleLocale">
          <span class="header-icon" aria-hidden="true">🌐</span>
          {{ t('app.languageCode') }}
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
</style>
