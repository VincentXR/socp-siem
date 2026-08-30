import { computed, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { currentSession, logout, setUnauthorizedHandler } from '../api'
import { normalizeLocale, setLocale } from '../i18n/locale-manager'
import { useI18n } from './useI18n'

export function useAuth() {
  const currentUser = ref('')
  const currentRole = ref('')
  const isAuthed = ref(false)
  const userInitials = computed(() => (currentUser.value || 'SY').slice(0, 2).toUpperCase())
  const queryClient = useQueryClient()
  const { t } = useI18n()

  function onLoginDone(user: string, role: string) {
    currentUser.value = user
    currentRole.value = role
    isAuthed.value = true
    try {
      localStorage.setItem('socp_user', user)
      localStorage.setItem('socp_role', role)
    } catch { /* display metadata only */ }
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
      return true
    } catch {
      currentUser.value = ''
      currentRole.value = ''
      isAuthed.value = false
      return false
    }
  }

  return { currentUser, currentRole, isAuthed, userInitials, onLoginDone, doLogout, initAuth }
}
