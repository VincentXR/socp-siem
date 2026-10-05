import { computed, inject } from 'vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { normalizeRole } from '../app/roles'

/** Domain controllers retain their admin/analyst role boundary.
 * A delegated SOAR permission does not grant writes in unrelated domains. */
export function useWriteAccess() {
  const state = inject(WORKBENCH_STATE, null)
  return computed(() => ['admin', 'analyst'].includes(normalizeRole(state?.currentRole.value)))
}
