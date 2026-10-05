import { computed, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { currentSession, listOperators, logout, setUnauthorizedHandler } from '../api'
import { normalizeLocale, setLocale } from '../i18n/locale-manager'
import { useI18n } from './useI18n'

export function useAuth() {
  const currentUser = ref('')
  const currentRole = ref('')
  const currentTenant = ref('')
  const currentPermissions = ref<string[]>([])
  const operatorOptions = ref<string[]>([])
  const operatorLabels = ref<Record<string, string>>({})
  const isAuthed = ref(false)
  const authReady = ref(false)
  const userInitials = computed(() => (currentUser.value || 'SY').slice(0, 2).toUpperCase())
  const queryClient = useQueryClient()
  const { t } = useI18n()

  function onLoginDone(user: string, role: string, tenant = 'default', permissions: string[] = []) {
    currentTenant.value = tenant
    currentPermissions.value = permissions
    currentUser.value = user
    currentRole.value = role
    operatorOptions.value = user ? [user] : []
    operatorLabels.value = {}
    isAuthed.value = true
    try {
      localStorage.setItem('socp_user', user)
      localStorage.setItem('socp_role', role)
    } catch { /* display metadata only */ }
    return refreshOperatorOptions()
  }

  async function refreshOperatorOptions(): Promise<void> {
    const user = currentUser.value
    const tenant = currentTenant.value
    if (!user) return
    const current = () => currentUser.value === user && currentTenant.value === tenant
    try {
      const directory = await listOperators()
      if (!current()) return
      operatorLabels.value = Object.fromEntries(directory.items.map(item => [item.id, item.label && item.label !== item.id ? `${item.label} (${item.id})` : item.id]))
      operatorOptions.value = Array.from(new Set(directory.items.map(item => item.id).filter(Boolean)))
    } catch {
      if (!current()) return
      // The session subject is still a valid assignment target when the
      // optional directory endpoint is unavailable.
      operatorOptions.value = [currentUser.value]
    }
  }

  async function doLogout() {
    await queryClient.cancelQueries()
    try { await logout() } catch { /* an expired session is already logged out */ }
    currentUser.value = ''
    currentRole.value = ''
    currentTenant.value = ''
    currentPermissions.value = []
    operatorOptions.value = []; operatorLabels.value = {}
    isAuthed.value = false
    try {
      localStorage.removeItem('socp_user')
      localStorage.removeItem('socp_role')
      localStorage.removeItem('socp_token')
      localStorage.removeItem('socp.search.saved-queries')
    } catch { /* ignore unavailable browser storage */ }
    location.reload()
  }

  async function initAuth(): Promise<boolean> {
    setUnauthorizedHandler(() => {
      ElMessage.warning(t('errors.UNAUTHORIZED'))
      window.setTimeout(() => { void doLogout() }, 600)
    })
    try {
      const session = await currentSession()
      const profileLocale = normalizeLocale(session.locale)
      if (profileLocale) setLocale(profileLocale)
      await onLoginDone(session.username, session.role, session.tenant, session.permissions ?? [])
      return true
    } catch {
      currentUser.value = ''
      currentRole.value = ''
      currentTenant.value = ''
      currentPermissions.value = []
      isAuthed.value = false
      operatorOptions.value = []; operatorLabels.value = {}
      return false
    } finally {
      authReady.value = true
    }
  }

  return { currentUser, currentRole, currentTenant, currentPermissions, operatorOptions, operatorLabels, isAuthed, authReady, userInitials, onLoginDone, doLogout, initAuth }
}
