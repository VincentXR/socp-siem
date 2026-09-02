// SOCP 通用工具库：级别颜色映射（供各 app 复用）
export const SEVERITY_COLORS: Record<string, string> = {
  // Framework-independent fallback palette. The workbench resolves the same
  // semantic tones from CSS variables at runtime for light/dark themes.
  CRITICAL: '#dc2626',
  HIGH: '#dc2626',
  MEDIUM: '#b45309',
  LOW: '#667085',
  INFO: '#667085',
}

export function severityColor(level: string): string {
  return SEVERITY_COLORS[(level || 'INFO').toUpperCase()] ?? SEVERITY_COLORS.INFO
}
