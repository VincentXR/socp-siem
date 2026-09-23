import { del, downloadFile, get, post, put, type ApiRequestOptions } from './core'
import type { DataSourceType, FieldDef, LogCategory, LogSource, ParseRule, ReferenceSet, SearchResult, SinkTarget, Paged } from './models'
import { withQuery } from '../lib/query'

/** Always request the bounded, one-based source catalogue rather than its legacy array form. */
export const listSourcesPage = (page: number, size: number, q = '', options?: ApiRequestOptions) =>
  get<Paged<LogSource>>(withQuery('/search-config/api/v1/sources', { page, size, q: q.trim() || undefined }), options)
export const getSource = (id: string, options?: ApiRequestOptions) =>
  get<{ source: LogSource }>(`/search-config/api/v1/sources/${encodeURIComponent(id)}`, options)
export type LogSourceInput = Partial<Omit<LogSource, 'id' | 'createdAt'>> & Pick<LogSource, 'name' | 'type' | 'format' | 'enabled'>
export const createSource = (source: LogSourceInput) => post<LogSource>('/search-config/api/v1/sources', source)
export const updateSource = (id: string, source: LogSourceInput) => put<{ source: LogSource }>(`/search-config/api/v1/sources/${encodeURIComponent(id)}`, source)
export const deleteSource = (id: string) => del(`/search-config/api/v1/sources/${encodeURIComponent(id)}`)
export const renderConfig = () => post<string>('/search-config/api/v1/render')
export const listParseRules = (options?: ApiRequestOptions) => get<ParseRule[]>('/search-config/api/v1/parse-rules', options)
export const listParseRulesPage = (page: number, size: number, q = '', options?: ApiRequestOptions) =>
  get<Paged<ParseRule>>(withQuery('/search-config/api/v1/parse-rules', { page, size, q: q.trim() || undefined }), options)
export const getParseRule = (id: string, options?: ApiRequestOptions) =>
  get<ParseRule>(`/search-config/api/v1/parse-rules/${encodeURIComponent(id)}`, options)
export const resolveParseRules = async (ids: string[], options?: ApiRequestOptions): Promise<ParseRule[]> => {
  const batches: string[][] = []
  for (let start = 0; start < ids.length; start += 30) batches.push(ids.slice(start, start + 30))
  const pages = await Promise.all(batches.map(batch =>
    get<ParseRule[]>(withQuery('/search-config/api/v1/parse-rules/batch/resolve', { ids: batch.join(',') }), options)))
  return pages.flat()
}
export const createParseRule = (r: Partial<ParseRule>) => post<ParseRule>('/search-config/api/v1/parse-rules', r)
export const deleteParseRule = (id: string) => del(`/search-config/api/v1/parse-rules/${encodeURIComponent(id)}`)
export const previewParse = (body: { ruleId?: string; format?: string; pattern?: string; line: string }, options?: ApiRequestOptions) =>
  post<{ matched: boolean; fields: Record<string, string>; error?: string; rule?: string; format?: string }>('/search-config/api/v1/parse-rules/preview', body, options)
export const listOutputs = (options?: ApiRequestOptions) => get<SinkTarget[]>('/search-config/api/v1/outputs', options)
export const createOutput = (o: Partial<SinkTarget>) => post<SinkTarget>('/search-config/api/v1/outputs', o)
export const deleteOutput = (id: string) => del(`/search-config/api/v1/outputs/${encodeURIComponent(id)}`)

export const listDataSourceTypes = (options?: ApiRequestOptions) => get<DataSourceType[]>('/search-config/api/v1/meta/data-source-types', options)
export const createDataSourceType = (t: Partial<DataSourceType>) => post<DataSourceType>('/search-config/api/v1/meta/data-source-types', t)
export const deleteDataSourceType = (id: string) => del(`/search-config/api/v1/meta/data-source-types/${encodeURIComponent(id)}`)
export const listCategories = (options?: ApiRequestOptions) => get<LogCategory[]>('/search-config/api/v1/meta/categories', options)
export const createCategory = (c: Partial<LogCategory>) => post<LogCategory>('/search-config/api/v1/meta/categories', c)
export const deleteCategory = (id: string) => del(`/search-config/api/v1/meta/categories/${encodeURIComponent(id)}`)
export const listFields = (options?: ApiRequestOptions) => get<FieldDef[]>('/search-config/api/v1/meta/fields', options)
export const createField = (f: Partial<FieldDef>) => post<FieldDef>('/search-config/api/v1/meta/fields', f)
export const deleteField = (id: string) => del(`/search-config/api/v1/meta/fields/${encodeURIComponent(id)}`)
export type SearchRequestOptions = ApiRequestOptions & { cursor?: string | null; limit?: number; timeline?: boolean }
export const splSearch = (q: string, options: SearchRequestOptions = {}) => {
  const { cursor, limit, timeline, ...requestOptions } = options
  return get<SearchResult>(withQuery('/search-config/api/v1/search', { q, cursor, limit, timeline }), requestOptions)
}
export const exportSearch = (q: string, format = 'json', options: Pick<SearchRequestOptions, 'cursor' | 'limit'> = {}) =>
  downloadFile(withQuery('/search-config/api/v1/search/export', { q, format, ...options }), `search.${format}`)

export const listRefSets = (options?: ApiRequestOptions) => get<ReferenceSet[]>('/search-config/api/v1/reference-sets', options)
export const createRefSet = (r: { name: string; description?: string; entries: string[] }) => post<ReferenceSet>('/search-config/api/v1/reference-sets', r)
export const deleteRefSet = (id: string) => del(`/search-config/api/v1/reference-sets/${encodeURIComponent(id)}`)
export const addRefEntry = (id: string, value: string) => post<{ ok: boolean; size: number }>(`/search-config/api/v1/reference-sets/${encodeURIComponent(id)}/entries`, { value })

export const updateParseRule = (id: string, rule: Partial<ParseRule>) => put<ParseRule>(`/search-config/api/v1/parse-rules/${encodeURIComponent(id)}`, rule)
export const previewParseDraft = (rule: Partial<ParseRule>, line: string) => post<{ matched: boolean; fields: Record<string, string>; error?: string }>('/search-config/api/v1/parse-rules/preview-draft', { rule, line })
export const deleteRefEntry = (id: string, value: string) => del(withQuery(`/search-config/api/v1/reference-sets/${encodeURIComponent(id)}/entries`, { value }))
export const updateDataSourceType = (id: string, body: Partial<DataSourceType>) => put<DataSourceType>(`/search-config/api/v1/meta/data-source-types/${encodeURIComponent(id)}`, body)
export const updateCategory = (id: string, body: Partial<LogCategory>) => put<LogCategory>(`/search-config/api/v1/meta/categories/${encodeURIComponent(id)}`, body)
export const updateField = (id: string, body: Partial<FieldDef>) => put<FieldDef>(`/search-config/api/v1/meta/fields/${encodeURIComponent(id)}`, body)
