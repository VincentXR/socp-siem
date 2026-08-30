import { translate } from '../i18n/index.ts'

export type ImportRow = Record<string, string>

/** Parse a small CSV/JSON resource file selected in the workbench. */
export async function readImportRows(file: File): Promise<ImportRow[]> {
  const text = await file.text()
  if (file.name.toLowerCase().endsWith('.json')) {
    const parsed = JSON.parse(text) as unknown
    const rows = Array.isArray(parsed)
      ? parsed
      : parsed && typeof parsed === 'object' && Array.isArray((parsed as { items?: unknown }).items)
        ? (parsed as { items: unknown[] }).items
        : []
    if (!rows.every(row => row && typeof row === 'object' && !Array.isArray(row))) {
      throw new Error(translate('errors.INVALID_JSON_ARRAY'))
    }
    return rows as ImportRow[]
  }

  const lines = text.replace(/^\uFEFF/, '').split(/\r?\n/).filter(line => line.trim())
  if (lines.length < 2) throw new Error(translate('errors.CSV_NEEDS_ROWS'))
  const headers = parseCsvLine(lines[0]).map(header => header.trim())
  if (headers.some(header => !header)) throw new Error(translate('errors.CSV_EMPTY_HEADER'))
  return lines.slice(1).map(line => {
    const values = parseCsvLine(line)
    return Object.fromEntries(headers.map((header, index) => [header, values[index]?.trim() ?? '']))
  })
}

function parseCsvLine(line: string): string[] {
  const values: string[] = []
  let value = ''
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const char = line[index]
    if (char === '"') {
      if (quoted && line[index + 1] === '"') {
        value += '"'
        index += 1
      } else {
        quoted = !quoted
      }
    } else if (char === ',' && !quoted) {
      values.push(value)
      value = ''
    } else {
      value += char
    }
  }
  if (quoted) throw new Error(translate('errors.CSV_UNCLOSED_QUOTE'))
  values.push(value)
  return values
}
