import { computed, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { currentSession, listOperators, logout, setUnauthorizedHandler } from '../api'
import { normalizeLocale, setLocale } from '../i18n/locale-manager'
import { useI18n } from './useI18n'

export function useAuth() {
  const currentUser = ref('')
  const currentRole = ref('')
  const operatorOptions = ref<string[]>([])
  const isAuthed = ref(false)
  const userInitials = computed(() => (currentUser.value || 'SY').slice(0, 2).toUpperCase())
  const queryClient = useQueryClient()
  const { t } = useI18n()

  function onLoginDone(user: string, role: string) {
    currentUser.value = user
    currentRole.value = role
    operatorOptions.value = user ? [user] : []
    isAuthed.value = true
    try {
      localStorage.setItem('socp_user', user)
      localStorage.setItem('socp_role', role)
    } catch { /* display metadata only */ }
    void refreshOperatorOptions()
  }

  async function refreshOperatorOptions(): Promise<void> {
    if (!currentUser.value) return
    try {
      const directory = await listOperators()
      operatorOptions.value = Array.from(new Set([
        ...directory.items.map(item => item.id).filter(Boolean),
        currentUser.value,
      ]))
    } catch {
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
    isAuthed.value = false
    try {
      localStorage.removeItem('socp_user')
      localStorage.removeItem('socp_role')
      localStorage.removeItem('socp_token')
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
      onLoginDone(session.username, session.role)
      await refreshOperatorOptions()
      return true
    } catch {
      currentUser.value = ''
      currentRole.value = ''
      isAuthed.value = false
      operatorOptions.value = []
      return false
    }
  }

  return { currentUser, currentRole, operatorOptions, isAuthed, userInitials, onLoginDone, doLogout, initAuth }
}
