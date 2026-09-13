import { useSoarAccess } from './useSoarAccess'

/** Reflect the admin/analyst write boundary used by domain controllers. */
export function useWriteAccess() {
  return useSoarAccess().canEdit
}
