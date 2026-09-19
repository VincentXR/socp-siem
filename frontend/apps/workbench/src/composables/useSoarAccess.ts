import { computed, inject } from 'vue'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { normalizeRole } from '../app/roles.ts'

/** Permissions enforced by the SOAR controller. Keep this list aligned with
 * platform/socp-auth Permission.roleDefaults so the workbench does not render
 * commands that the owning service will reject with a 403. */
export type SoarPermission =
  | 'soar:view'
  | 'soar:edit'
  | 'soar:publish'
  | 'soar:execute'
  | 'soar:approve'
  | 'soar:task:complete'
  | 'soar:connections:view'
  | 'soar:connections:manage'
  | 'soar:operations'

const ALL_PERMISSIONS: ReadonlySet<SoarPermission> = new Set([
  'soar:view',
  'soar:edit',
  'soar:publish',
  'soar:execute',
  'soar:approve',
  'soar:task:complete',
  'soar:connections:view',
  'soar:connections:manage',
  'soar:operations',
])

const ROLE_PERMISSIONS: Readonly<Record<string, ReadonlySet<SoarPermission>>> = {
  admin: ALL_PERMISSIONS,
  approver: new Set(['soar:view', 'soar:approve', 'soar:task:complete']),
  analyst: new Set([
    'soar:view',
    'soar:edit',
    'soar:execute',
    'soar:task:complete',
    'soar:connections:view',
  ]),
  viewer: new Set(['soar:view']),
}

export function normalizeSoarRole(role: string | undefined | null): string {
  return normalizeRole(role)
}

export function hasSoarPermission(role: string | undefined | null, permission: SoarPermission): boolean {
  return (ROLE_PERMISSIONS[normalizeSoarRole(role)] ?? new Set()).has(permission)
}

/** Expose the same fine-grained capabilities as the backend to SOAR views. */
export function useSoarAccess() {
  const state = inject(WORKBENCH_STATE, null)
  const role = computed(() => normalizeSoarRole(state?.currentRole.value))
  const allowed = (permission: SoarPermission) => computed(() => hasSoarPermission(role.value, permission))

  return {
    role,
    canView: allowed('soar:view'),
    canEdit: allowed('soar:edit'),
    canPublish: allowed('soar:publish'),
    canExecute: allowed('soar:execute'),
    canApprove: allowed('soar:approve'),
    canCompleteTasks: allowed('soar:task:complete'),
    canViewConnections: allowed('soar:connections:view'),
    canManageConnections: allowed('soar:connections:manage'),
    canOperate: allowed('soar:operations'),
  }
}
