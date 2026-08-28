import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isMenuKey, menuForPath, pathForMenu, type MenuKey } from '../app/routes'

/** Vue Router adapter for the single-shell workbench. */
export function useWorkbenchRoute() {
  const route = useRoute()
  const router = useRouter()
  const activeMenu = computed<MenuKey>(() => menuForPath(route.path))

  function navigate(menu: string, replace = false): void {
    if (!isMenuKey(menu)) return
    const path = pathForMenu(menu)
    if (route.path === path) return
    void (replace ? router.replace(path) : router.push(path))
  }

  return { activeMenu, navigate }
}
