/**
 * Roles arrive from different identity providers with slightly different
 * casing/prefix conventions. Keep the normalization in one place so menu
 * visibility and action-level guards cannot disagree about `ROLE_VIEWER`.
 */
export function normalizeRole(role: string | undefined | null): string {
  return (role ?? '').trim().toLowerCase().replace(/^role_/, '')
}

export const KNOWN_ROLES = new Set(['admin', 'analyst', 'approver', 'viewer'])
