import { onMounted, onUnmounted, ref } from 'vue'
import { isMenuKey, menuForPath, pathForMenu, type MenuKey } from '../app/routes'

/** Lightweight URL routing for the single-shell workbench, including deep links and back/forward. */
export function useWorkbenchRoute() {
  const activeMenu = ref<MenuKey>(menuForPath(window.location.pathname))

  function navigate(menu: string, replace = false): void {
    if (!isMenuKey(menu)) return
    activeMenu.value = menu
    const path = pathForMenu(menu)
    if (window.location.pathname === path) return
    window.history[replace ? 'replaceState' : 'pushState']({ menu }, '', path)
  }

  function syncFromLocation(): void {
    activeMenu.value = menuForPath(window.location.pathname)
  }

  onMounted(() => window.addEventListener('popstate', syncFromLocation))
  onUnmounted(() => window.removeEventListener('popstate', syncFromLocation))

  return { activeMenu, navigate }
}
