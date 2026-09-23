/** Split only at a pipeline operator outside strings and grouped filters. */
export function splitPipeline(rawQuery: string): { filter: string; pipeline: string } {
  let quote: string | null = null
  let escaped = false
  let depth = 0
  for (let index = 0; index < rawQuery.length; index += 1) {
    const character = rawQuery[index]
    if (quote) {
      if (escaped) escaped = false
      else if (character === '\\') escaped = true
      else if (character === quote) quote = null
      continue
    }
    if (character === '"' || character === "'") quote = character
    else if (character === '(') depth += 1
    else if (character === ')') depth = Math.max(0, depth - 1)
    else if (character === '|' && depth === 0) return { filter: rawQuery.slice(0, index), pipeline: rawQuery.slice(index).trim() }
  }
  return { filter: rawQuery, pipeline: '' }
}

/** Match the SPL lexer's quoted-string escaping, preserving literal backslashes. */
export function appendSearchFilter(rawQuery: string, field: string, value?: string): string {
  const name = field.trim()
  if (!name) return rawQuery
  const literal = value?.replaceAll('\\', '\\\\').replaceAll('"', '\\"')
  const clause = value === undefined ? `${name}=` : `${name}="${literal}"`
  const { filter, pipeline } = splitPipeline(rawQuery.trim() || '*')
  const base = filter.trim() === '*' ? '' : filter.trim()
  return `${base ? `(${base}) AND ` : ''}${clause}${pipeline ? ` ${pipeline}` : ''}`
}
