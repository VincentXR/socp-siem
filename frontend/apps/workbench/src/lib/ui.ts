/** Shared UI formatting helpers. All user-facing copy comes from Vue I18n. */

import { severityColor } from '@socp/library'
import { formatDate, formatNumber, translate } from '../i18n'

export function sevColor(s: string): string {
  return severityColor(s)
}

export function sevBg(s: string): string {
  const c = sevColor(s)
  return `background:${c};color:#fff;border-radius:4px;padding:0 8px;font-size:12px;font-weight:600;display:inline-block;line-height:20px`
}

export function riskColor(level: string): string {
  const map: Record<string, string> = { critical: '#f56c6c', high: '#e63946', medium: '#e6a23c', low: '#909399' }
  return map[(level || '').toLowerCase()] ?? '#909399'
}

export function relTime(iso?: string): string {
  if (!iso) return translate('time.notAvailable')
  const timestamp = Date.parse(iso)
  if (Number.isNaN(timestamp)) return iso
  const diff = Math.max(0, Date.now() - timestamp)
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return translate('time.justNow')
  if (minutes < 60) return translate('time.minutesAgo', { count: minutes })
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return translate('time.hoursAgo', { count: hours })
  return translate('time.daysAgo', { count: Math.floor(hours / 24) })
}

export function fmtTime(iso?: string): string {
  if (!iso) return translate('time.notAvailable')
  const timestamp = Date.parse(iso)
  return Number.isNaN(timestamp) ? iso : formatDate(timestamp, 'dateTime')
}

export function fmtBytes(n: number): string {
  if (n == null || Number.isNaN(n)) return `0 ${translate('units.bytes')}`
  if (n < 1024) return `${formatNumber(n, 'integer')} ${translate('units.bytes')}`
  if (n < 1024 * 1024) return `${formatNumber(n / 1024, 'decimal')} KB`
  if (n < 1024 * 1024 * 1024) return `${formatNumber(n / 1024 / 1024, 'decimal')} MB`
  return `${formatNumber(n / 1024 / 1024 / 1024, 'decimal')} GB`
}

export function themeColor(key: string): string {
  const dark = document.documentElement.classList.contains('dark')
  const map: Record<string, string> = {
    label: dark ? '#e6edf3' : '#1f2328',
    axis: dark ? '#8b949e' : '#57606a',
    grid: dark ? 'rgba(139,148,158,.08)' : 'rgba(87,96,106,.08)',
  }
  return map[key] ?? (dark ? '#8b949e' : '#57606a')
}
