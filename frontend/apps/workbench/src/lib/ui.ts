/**
 * 前端 UI 工具库（从 App.vue 抽取的纯函数，页面组件共用）。
 */

import { severityColor } from '@socp/library'

/** 严重级别颜色映射 */
export function sevColor(s: string): string {
  return severityColor(s)
}

/** 严重级别徽标内联样式（背景色 + 白色文字） */
export function sevBg(s: string): string {
  const c = sevColor(s)
  return `background:${c};color:#fff;border-radius:4px;padding:0 8px;font-size:12px;font-weight:600;display:inline-block;line-height:20px`
}

/** 风险档位颜色（UEBA 风险看板） */
export function riskColor(level: string): string {
  const map: Record<string, string> = { critical: '#f56c6c', high: '#e63946', medium: '#e6a23c', low: '#909399' }
  return map[(level || '').toLowerCase()] ?? '#909399'
}

/** 相对时间：xx 分钟前 / x 小时前 / x 天前 */
export function relTime(iso?: string): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (isNaN(t)) return iso
  const diff = Math.max(0, Date.now() - t)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`
  return `${Math.floor(hr / 24)} 天前`
}

/** 格式化时间戳（ISO → 本地 YYYY-MM-DD HH:mm:ss） */
export function fmtTime(iso?: string): string {
  if (!iso) return '—'
  return iso.slice(0, 19).replace('T', ' ')
}

/** 字节数人类可读 */
export function fmtBytes(n: number): string {
  if (n == null || isNaN(n)) return '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 主题色（深色/浅色自适应，供 echarts 图表复用） */
export function themeColor(key: string): string {
  const dark = document.documentElement.classList.contains('dark')
  const map: Record<string, string> = {
    label: dark ? '#e6edf3' : '#1f2328',
    axis: dark ? '#8b949e' : '#57606a',
    grid: dark ? 'rgba(139,148,158,.08)' : 'rgba(87,96,106,.08)',
  }
  return map[key] ?? (dark ? '#8b949e' : '#57606a')
}
