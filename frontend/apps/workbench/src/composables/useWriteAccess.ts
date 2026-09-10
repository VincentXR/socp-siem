import { computed, inject } from 'vue'
import { WORKBENCH_STATE } from '../app/workbenchState'

/** Reflect the admin/analyst write boundary used by domain controllers. */
export function useWriteAccess() {
  const state = inject(WORKBENCH_STATE, null)
  return computed(() => ['admin', 'analyst'].includes((state?.currentRole.value ?? '').toLowerCase().replace(/^role_/, '')))
}
