import { del, downloadFile, get, post, put, type ApiRequestOptions } from './core'
import type { DataSourceType, FieldDef, LogCategory, LogSource, ParseRule, ReferenceSet, SearchResult, SinkTarget } from './models'
import { withQuery } from '../lib/query'

export const listSources = () => get<LogSource[]>('/search-config/api/v1/sources')
export type LogSourceInput = Partial<Omit<LogSource, 'id' | 'createdAt'>> & Pick<LogSource, 'name' | 'type' | 'format' | 'enabled'>
export const createSource = (source: LogSourceInput) => post<LogSource>('/search-config/api/v1/sources', source)
export const updateSource = (id: string, source: LogSourceInput) => put<{ source: LogSource }>(`/search-config/api/v1/sources/${encodeURIComponent(id)}`, source)
export const deleteSource = (id: string) => del(`/search-config/api/v1/sources/${encodeURIComponent(id)}`)
export const renderConfig = () => post<string>('/search-config/api/v1/render')
export const listParseRules = () => get<ParseRule[]>('/search-config/api/v1/parse-rules')
export const createParseRule = (r: Partial<ParseRule>) => post<ParseRule>('/search-config/api/v1/parse-rules', r)
export const deleteParseRule = (id: string) => del(`/search-config/api/v1/parse-rules/${encodeURIComponent(id)}`)
export const previewParse = (body: { ruleId?: string; format?: string; pattern?: string; line: string }) =>
  post<{ matched: boolean; fields: Record<string, string>; error?: string; rule?: string; format?: string }>('/search-config/api/v1/parse-rules/preview', body)
export const listOutputs = () => get<SinkTarget[]>('/search-config/api/v1/outputs')
export const createOutput = (o: Partial<SinkTarget>) => post<SinkTarget>('/search-config/api/v1/outputs', o)
export const deleteOutput = (id: string) => del(`/search-config/api/v1/outputs/${encodeURIComponent(id)}`)

export const listDataSourceTypes = () => get<DataSourceType[]>('/search-config/api/v1/meta/data-source-types')
export const createDataSourceType = (t: Partial<DataSourceType>) => post<DataSourceType>('/search-config/api/v1/meta/data-source-types', t)
export const deleteDataSourceType = (id: string) => del(`/search-config/api/v1/meta/data-source-types/${encodeURIComponent(id)}`)
export const listCategories = () => get<LogCategory[]>('/search-config/api/v1/meta/categories')
export const createCategory = (c: Partial<LogCategory>) => post<LogCategory>('/search-config/api/v1/meta/categories', c)
export const deleteCategory = (id: string) => del(`/search-config/api/v1/meta/categories/${encodeURIComponent(id)}`)
export const listFields = () => get<FieldDef[]>('/search-config/api/v1/meta/fields')
export const createField = (f: Partial<FieldDef>) => post<FieldDef>('/search-config/api/v1/meta/fields', f)
export const deleteField = (id: string) => del(`/search-config/api/v1/meta/fields/${encodeURIComponent(id)}`)
export const splSearch = (q: string, options?: ApiRequestOptions) => get<SearchResult>(withQuery('/search-config/api/v1/search', { q }), options)
export const exportSearch = (q: string, format = 'json') => downloadFile(withQuery('/search-config/api/v1/search/export', { q, format }), `search.${format}`)

export const listRefSets = () => get<ReferenceSet[]>('/search-config/api/v1/reference-sets')
export const createRefSet = (r: { name: string; description?: string; entries: string[] }) => post<ReferenceSet>('/search-config/api/v1/reference-sets', r)
export const deleteRefSet = (id: string) => del(`/search-config/api/v1/reference-sets/${encodeURIComponent(id)}`)
export const addRefEntry = (id: string, value: string) => post<{ ok: boolean; size: number }>(`/search-config/api/v1/reference-sets/${encodeURIComponent(id)}/entries`, { value })
