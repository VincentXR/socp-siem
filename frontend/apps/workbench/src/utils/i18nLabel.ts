/**
 * Vue I18n returns the requested key verbatim when no locale defines it (and
 * every locale is defined in the same message packs, so a miss is a genuine
 * missing key). `t(key) || fallback` therefore never falls back: the key path
 * itself is truthy and wins, so the fallback copy is dead code and the
 * operator sees `search.sources.local-cache`.
 *
 * tOr treats "translation === key" (and empty text) as a miss so a static
 * fallback is reachable again.
 */
export function tOr(t: (key: string) => string, key: string, fallback: string): string {
  const text = t(key)
  return !text || text === key ? fallback : text
}
