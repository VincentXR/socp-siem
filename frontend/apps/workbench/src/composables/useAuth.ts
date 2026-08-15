import { computed, ref } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { clearToken, setToken, setUnauthorizedHandler } from '../api'

function decodeJwtPayload(token: string): Record<string, any> | null {
  try {
    const payload = token.split('.')[1]
    return JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
  } catch { return null }
}

export function useAuth() {
  const currentUser = ref('')
  const currentRole = ref('')
  const isAuthed = ref(false)
  const userInitials = computed(() => (currentUser.value || 'SY').slice(0, 2).toUpperCase())
  const queryClient = useQueryClient()

  function onLoginDone(user: string, role: string) {
    currentUser.value = user
    currentRole.value = role
    isAuthed.value = true
  }

  function doLogout() {
    void queryClient.cancelQueries()
    clearToken()
    currentUser.value = ''
    currentRole.value = ''
    isAuthed.value = false
    try {
      localStorage.removeItem('socp_user')
      localStorage.removeItem('socp_role')
    } catch { /* ignore */ }
    location.reload()
  }

  function initAuth() {
    setUnauthorizedHandler(() => {
      ElMessage.warning('鐧诲綍宸茶繃鏈燂紝璇烽噸鏂扮櫥褰?')
      setTimeout(doLogout, 600)
    })

    const oidcToken = new URLSearchParams(window.location.search).get('socp_oidc_token')
    if (oidcToken) {
      setToken(oidcToken)
      const claims = decodeJwtPayload(oidcToken)
      if (claims) {
        try {
          localStorage.setItem('socp_user', claims.sub || 'socp-user')
          localStorage.setItem('socp_role', claims.role || 'analyst')
        } catch { /* ignore */ }
      }
      window.history.replaceState({}, '', window.location.pathname)
    }

    try {
      currentUser.value = localStorage.getItem('socp_user') || ''
      currentRole.value = localStorage.getItem('socp_role') || ''
      isAuthed.value = !!(localStorage.getItem('socp_token') && currentUser.value)
    } catch {
      currentUser.value = ''
      currentRole.value = ''
      isAuthed.value = false
    }
    return isAuthed.value
  }

  return { currentUser, currentRole, isAuthed, userInitials, onLoginDone, doLogout, initAuth }
}
