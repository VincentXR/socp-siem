import { translate } from '../i18n/index.ts'

export type ImportRow = Record<string, unknown>
const MAX_BYTES = 2 * 1024 * 1024
const MAX_ROWS = 500

/** Parse a bounded CSV/JSON resource file; reject ambiguous rows instead of dropping cells. */
export async function readImportRows(file: File): Promise<ImportRow[]> {
  if (!/\.(csv|json)$/i.test(file.name) || file.size > MAX_BYTES) throw new Error(translate('errors.IMPORT_LIMITS'))
  const text = (await file.text()).replace(/^\uFEFF/, '')
  if (new TextEncoder().encode(text).length > MAX_BYTES) throw new Error(translate('errors.IMPORT_LIMITS'))
  if (file.name.toLowerCase().endsWith('.json')) {
    const parsed = JSON.parse(text) as unknown
    const rows = Array.isArray(parsed) ? parsed
      : parsed && typeof parsed === 'object' && Array.isArray((parsed as { items?: unknown }).items)
        ? (parsed as { items: unknown[] }).items : null
    if (!rows || !rows.every(row => row && typeof row === 'object' && !Array.isArray(row))) {
      throw new Error(translate('errors.INVALID_JSON_ARRAY'))
    }
    if (!rows.length || rows.length > MAX_ROWS) throw new Error(translate('errors.IMPORT_LIMITS'))
    return rows as ImportRow[]
  }

  const records: string[][] = []
  let values: string[] = [], value = '', quoted = false, closed = false, started = false
  const finishField = () => { values.push(value.trim()); value = ''; closed = false }
  const finishRecord = () => {
    finishField()
    if (started) records.push(values)
    if (records.length > MAX_ROWS + 1) throw new Error(translate('errors.IMPORT_LIMITS'))
    values = []; started = false
  }
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') { value += '"'; index++ }
      else if (char === '"') { quoted = false; closed = true }
      else value += char
    } else if (char === ',') { started = true; finishField() }
    else if (char === '\n' || char === '\r') {
      if (char === '\r' && text[index + 1] === '\n') index++
      finishRecord()
    } else if (char === '"' && !closed && !value.trim()) { quoted = true; started = true; value = '' }
    else {
      if (char === '"' || closed && char.trim()) throw new Error(translate('errors.CSV_INVALID_RECORD'))
      if (!closed) value += char
      if (char.trim()) started = true
    }
  }
  if (quoted) throw new Error(translate('errors.CSV_UNCLOSED_QUOTE'))
  finishRecord()
  if (records.length < 2) throw new Error(translate('errors.CSV_NEEDS_ROWS'))
  const headers = records[0]
  if (headers.some(header => !header)) throw new Error(translate('errors.CSV_EMPTY_HEADER'))
  if (new Set(headers).size !== headers.length) throw new Error(translate('errors.CSV_INVALID_RECORD'))
  return records.slice(1).map(row => {
    if (row.length !== headers.length) throw new Error(translate('errors.CSV_INVALID_RECORD'))
    return Object.fromEntries(headers.map((header, index) => [header, row[index]]))
  })
}
