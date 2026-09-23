import type { IocInput } from '../api/threat'
import type { ImportRow } from './resource-import'
import { translate } from '../i18n'

export const IOC_TYPES = ['IP', 'DOMAIN', 'URL', 'SHA256', 'MD5', 'EMAIL']
const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']

/** Validate every row before presenting a single import confirmation. */
export function prepareIocImport(rows: ImportRow[]): IocInput[] {
  if (!rows.length || rows.length > 500) throw new Error(translate('threat.importLimits'))
  return rows.map((row, index) => {
    const invalid = () => new Error(translate('threat.importRowInvalid', { row: index + 1 }))
    const text = (key: string) => {
      if (row[key] == null) return ''
      if (typeof row[key] !== 'string') throw invalid()
      return (row[key] as string).trim()
    }
    const tags = Array.isArray(row.tags)
      ? row.tags.map(tag => { if (typeof tag !== 'string') throw invalid(); return tag.trim() }).filter(Boolean)
      : text('tags').split(/[,;\n]+/).map(tag => tag.trim()).filter(Boolean)
    const item = { type: text('type').toUpperCase() || 'IP', value: text('value'),
      severity: text('severity').toUpperCase() || 'HIGH', source: text('source') || 'import',
      description: text('description'), tags }
    if (!IOC_TYPES.includes(item.type) || !item.value || item.value.length > 2048
        || !SEVERITIES.includes(item.severity) || item.source.length > 256 || item.description.length > 4096
        || tags.length > 32 || tags.some(tag => tag.length > 128)) throw invalid()
    return item
  })
}
