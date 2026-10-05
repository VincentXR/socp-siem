/**
 * Roles arrive from different identity providers with slightly different
 * casing/prefix conventions. Keep the normalization in one place so menu
 * visibility and action-level guards cannot disagree about `ROLE_VIEWER`.
 */
export function normalizeRole(role: string | undefined | null): string {
  return (role ?? '').trim().toLowerCase().replace(/^role_/, '')
}

export const KNOWN_ROLES = new Set(['admin', 'analyst', 'viewer'])

const SOAR_WORKBENCH_PERMISSIONS = new Set([
  'soar:edit', 'soar:publish', 'soar:execute', 'soar:approve',
  'soar:task:complete', 'soar:connections:manage', 'soar:operations',
])

/** Keep every SOAR entry point aligned with the verified session capabilities. */
export function canOpenSoarWorkbench(role: string | undefined | null, permissions: readonly string[] = []): boolean {
  const normalizedRole = normalizeRole(role)
  return KNOWN_ROLES.has(normalizedRole) && (normalizedRole === 'admin' || normalizedRole === 'analyst'
    || permissions.some(permission => SOAR_WORKBENCH_PERMISSIONS.has(permission)))
}
