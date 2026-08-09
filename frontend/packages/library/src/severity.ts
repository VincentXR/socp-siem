// SOCP 通用工具库：级别颜色映射（供各 app 复用）
export const SEVERITY_COLORS: Record<string, string> = {
  CRITICAL: '#f56c6c',
  HIGH: '#e63946',
  MEDIUM: '#e6a23c',
  LOW: '#909399',
  INFO: '#909399',
}

export function severityColor(level: string): string {
  return SEVERITY_COLORS[(level || 'INFO').toUpperCase()] ?? SEVERITY_COLORS.INFO
}
