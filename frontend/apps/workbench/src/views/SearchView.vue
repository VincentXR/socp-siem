<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import 'element-plus/es/components/tooltip/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElForm } from 'element-plus/es/components/form/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import ElTooltip from 'element-plus/es/components/tooltip/index.mjs'
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import RowActivate from '../components/RowActivate.vue'
import FormField from '../components/FormField.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useFocusReturn } from '../composables/useFocusReturn'
import { exportSearch, listAlarmsByEvent, listFields, splSearch, type Alarm, type FieldDef, type SearchEvent, type SearchResult } from '../api'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { stageDetectionSample } from '../lib/detection-sample'
import { WORKBENCH_STATE } from '../app/workbenchState'
import { useWriteAccess } from '../composables/useWriteAccess'
import { appendSearchFilter, splitPipeline } from '../lib/search-query'

const { t } = useI18n()
const canWrite = useWriteAccess()
const identity = inject(WORKBENCH_STATE, null)
const route = useRoute()
const router = useRouter()
type TimeRangeKey = '15m' | '30m' | '1h' | '6h' | '24h' | 'all' | 'custom'
type EventSortOrder = 'ascending' | 'descending'

const routeQuery = typeof route.query.q === 'string' ? route.query.q : ''
const routeRange = typeof route.query.range === 'string' ? route.query.range : ''
const eventSortFields = ['timestamp', 'source', 'host', 'severity', 'msg'] as const
const routeSort = typeof route.query.sort === 'string' && eventSortFields.includes(route.query.sort as typeof eventSortFields[number]) ? route.query.sort : ''
const routeOrder = route.query.order === 'ascending' || route.query.order === 'descending' ? route.query.order as EventSortOrder : null
const validTimeRanges: TimeRangeKey[] = ['15m', '30m', '1h', '6h', '24h', 'all', 'custom']
const query = ref(typeof route.query.draft === 'string' ? route.query.draft : routeQuery || '*')
const investigationReturn = computed(() => typeof route.query.returnTo === 'string' && /^\/(alarms|cases|assistant)(?:[?#]|$)/.test(route.query.returnTo) ? route.query.returnTo : '')
const result = ref<SearchResult | null>(null)
const loading = ref(false)
const error = ref('')
const currentPage = ref(1)
const pageSize = ref([25, 50, 100].includes(Number(route.query.size)) ? Number(route.query.size) : 50)
const pageCursors = ref<Array<string | null>>([null])
const pageSizes = [25, 50, 100]
const MAX_BROWSE_ROWS = 10_000
const MAX_DEEP_LINK_PAGE = 20
const timeRangeOptions: Array<{ key: TimeRangeKey; label: string; durationMs?: number }> = [
  { key: '15m', label: 'search.timeRanges.last15Minutes', durationMs: 15 * 60_000 },
  { key: '30m', label: 'search.timeRanges.last30Minutes', durationMs: 30 * 60_000 },
  { key: '1h', label: 'search.timeRanges.lastHour', durationMs: 60 * 60_000 },
  { key: '6h', label: 'search.timeRanges.last6Hours', durationMs: 6 * 60 * 60_000 },
  { key: '24h', label: 'search.timeRanges.last24Hours', durationMs: 24 * 60 * 60_000 },
  { key: 'all', label: 'search.timeRanges.all' },
  { key: 'custom', label: 'experience.custom' },
]
const selectedTimeRange = ref<TimeRangeKey>(validTimeRanges.includes(routeRange as TimeRangeKey) ? routeRange as TimeRangeKey : '30m')
const activeTimeRange = ref<TimeRangeKey>(selectedTimeRange.value)
const activeQuery = ref('')
const activeRawQuery = ref(query.value.trim() || '*')
const activeEndAt = ref(Date.now())
const fieldBrowserOpen = ref(false)
const customFrom = ref(typeof route.query.from === 'string' ? utcDateInput(route.query.from) : new Date(Date.now() - 86400_000).toISOString().slice(0, 19))
const customTo = ref(typeof route.query.to === 'string' ? utcDateInput(route.query.to) : new Date().toISOString().slice(0, 19))
const activeFromAt = ref<number | null>(null)
const activeBounds = computed(() => activeFromAt.value === null ? 'UTC' : `${new Date(activeFromAt.value).toISOString()} → ${new Date(activeEndAt.value).toISOString()} · UTC`)
function utcDateInput(value: string): string {
  const millis = Date.parse(value)
  return Number.isFinite(millis) ? new Date(millis).toISOString().replace(/Z$/, '') : ''
}
function utcInput(value: string): number { return Date.parse(value.endsWith('Z') ? value : `${value}Z`) }
const queryInput = ref<InstanceType<typeof ElInput>>()
const hasDraftChanges = computed(() => (query.value.trim() || '*') !== activeRawQuery.value || selectedTimeRange.value !== activeTimeRange.value || (selectedTimeRange.value === 'custom' && (utcInput(customFrom.value) !== activeFromAt.value || utcInput(customTo.value) !== activeEndAt.value)))
const eventSortProp = ref<string>(routeSort)
const eventSortOrder = ref<EventSortOrder | null>(routeOrder)
const fieldDefs = ref<FieldDef[]>([])
const fieldKeyword = ref('')
const fieldsLoading = ref(false)
const fieldsError = ref('')
const selectedEvent = ref<SearchEvent | null>(null)
// The event drawer opens from a result row, so closing it must not drop focus.
const restoreEventFocus = useFocusReturn(computed(() => Boolean(selectedEvent.value)))
const relatedAlarms = ref<Alarm[]>([])
const relatedAlarmsLoading = ref(false)
const relatedAlarmsError = ref('')
let eventLineageToken = 0
const savedQueries = ref<Array<{ id: string; name: string; query: string; range: TimeRangeKey; from?: string; to?: string }>>([])
const selectedSavedQueryId = ref('')
const saveDialogVisible = ref(false)
const savedQueryName = ref('')
const savedQueryKey = computed(() => identity?.currentTenant?.value && identity.currentUser.value
  ? `socp.search.saved-queries.v2:${encodeURIComponent(identity.currentTenant.value)}:${encodeURIComponent(identity.currentUser.value)}` : '')
let requestSequence = 0
let cancelled = false
let requestController: AbortController | undefined
let syncedRouteKey = ''
onBeforeUnmount(() => { cancelled = true; requestSequence += 1; requestController?.abort(); eventLineageToken += 1 })
const examples = [
  'source=auth severity=HIGH',
  'msg contains "blocked" | top src_ip 5',
  'severity>=HIGH | timechart',
  'src_ip=10.0.0.9 OR user=admin',
  'source=web | count by http_method',
  'bytes>=1000 | head 10',
]
const maxStatCount = computed(() => Math.max(1, ...(result.value?.stat?.rows.map(row => Number(row.count)) ?? [1])))
const timelineRows = computed(() => result.value?.timeline ?? [])
const maxTimelineCount = computed(() => Math.max(1, ...timelineRows.value.map(row => Number(row.count))))
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('search-events')
const fallbackFields: FieldDef[] = [
  { id: 'timestamp', fieldName: 'timestamp', fieldLabel: 'Timestamp', fieldType: 'date', source: 'system', searchable: true, aggregatable: false, stored: true, description: 'Event time' },
  { id: 'source', fieldName: 'source', fieldLabel: 'Source', fieldType: 'string', source: 'system', searchable: true, aggregatable: true, stored: true, description: 'Log source' },
  { id: 'host', fieldName: 'host', fieldLabel: 'Host', fieldType: 'string', source: 'system', searchable: true, aggregatable: true, stored: true, description: 'Originating host' },
  { id: 'severity', fieldName: 'severity', fieldLabel: 'Severity', fieldType: 'enum', source: 'system', searchable: true, aggregatable: true, stored: true, description: 'Normalized severity' },
  { id: 'msg', fieldName: 'msg', fieldLabel: 'Message', fieldType: 'string', source: 'system', searchable: true, aggregatable: false, stored: true, description: 'Normalized message' },
  { id: 'src_ip', fieldName: 'src_ip', fieldLabel: 'Source IP', fieldType: 'ip', source: 'parse', searchable: true, aggregatable: true, stored: true, description: 'Parsed source address' },
  { id: 'user', fieldName: 'user', fieldLabel: 'User', fieldType: 'string', source: 'parse', searchable: true, aggregatable: true, stored: true, description: 'Parsed account name' },
]
const availableFields = computed(() => fieldDefs.value.length ? fieldDefs.value : fallbackFields)
const visibleFields = computed(() => {
  const keyword = fieldKeyword.value.trim().toLowerCase()
  if (!keyword) return availableFields.value
  return availableFields.value.filter(field => [field.fieldName, field.fieldLabel, field.description].some(value => String(value ?? '').toLowerCase().includes(keyword)))
})

function buildScopedQuery(rawQuery: string, range: TimeRangeKey, endAt = Date.now()): string {
  let normalized = rawQuery.trim() || '*'
  if (typeof route.query.sourceId === 'string' && route.query.sourceId.trim()) normalized = appendSearchFilter(normalized, 'source_id', route.query.sourceId)
  if (typeof route.query.eventId === 'string' && route.query.eventId.trim()) normalized = appendSearchFilter(normalized, 'eventId', route.query.eventId)
  const option = timeRangeOptions.find(candidate => candidate.key === range)
  const startAt = range === 'custom' ? activeFromAt.value : option?.durationMs ? endAt - option.durationMs : null
  if (startAt === null) return normalized
  const { filter, pipeline } = splitPipeline(normalized)
  const from = new Date(startAt).toISOString()
  const to = new Date(endAt).toISOString()
  const scopedFilter = `(${filter.trim() || '*'}) AND timestamp>=${from} AND timestamp<=${to}`
  return pipeline ? `${scopedFilter} ${pipeline}` : scopedFilter
}

const activeTimeRangeLabel = computed(() => {
  const option = timeRangeOptions.find(candidate => candidate.key === activeTimeRange.value) ?? timeRangeOptions[1]
  return t(option.label)
})

/** Backend search-backend enum; unknown or missing values keep the raw token. */
function sourceLabel(source: string | null | undefined): string {
  return tOr(t, `search.sources.${source ?? 'unspecified'}`, source ?? '—')
}

// Only applied state belongs in a result link; drafts never alter its query.
function routeKey(params: Record<string, unknown>): string {
  return JSON.stringify(['q', 'draft', 'range', 'from', 'to', 'sourceId', 'eventId', 'page', 'size', 'sort', 'order'].map(key => params[key] ?? null))
}

async function syncUrl(page = 1, history: 'push' | 'replace' = 'replace'): Promise<void> {
  if (route.name !== 'search') return
  const params = {
    ...route.query,
    q: activeRawQuery.value,
    draft: undefined,
    range: activeTimeRange.value,
    from: activeTimeRange.value === 'custom' && activeFromAt.value !== null ? new Date(activeFromAt.value).toISOString() : undefined,
    to: activeTimeRange.value === 'all' ? undefined : new Date(activeEndAt.value).toISOString(),
    size: pageSize.value !== 50 ? String(pageSize.value) : undefined,
    sort: eventSortProp.value || undefined,
    order: eventSortOrder.value || undefined,
    page: page > 1 ? String(page) : undefined,
  }
  syncedRouteKey = routeKey(params)
  await router[history]({ query: params })
}

function readRoute(): void {
  customFrom.value = typeof route.query.from === 'string' ? utcDateInput(route.query.from) : customFrom.value
  customTo.value = typeof route.query.to === 'string' ? utcDateInput(route.query.to) : customTo.value
  query.value = typeof route.query.draft === 'string' ? route.query.draft : typeof route.query.q === 'string' ? route.query.q : '*'
  selectedTimeRange.value = validTimeRanges.includes(route.query.range as TimeRangeKey) ? route.query.range as TimeRangeKey : '30m'
  pageSize.value = pageSizes.includes(Number(route.query.size)) ? Number(route.query.size) : 50
  eventSortProp.value = eventSortFields.includes(route.query.sort as typeof eventSortFields[number]) ? String(route.query.sort) : ''
  eventSortOrder.value = route.query.order === 'ascending' || route.query.order === 'descending' ? route.query.order : null
}

function restoreSearch(): void {
  // Generated SPL remains reviewable text until the analyst explicitly runs it.
  if (typeof route.query.draft === 'string') {
    requestController?.abort(); requestSequence++; loading.value = false
    result.value = null; error.value = ''; activeRawQuery.value = ''; activeQuery.value = ''
    query.value = route.query.draft
    return
  }
  const page = Number(route.query.page)
  const targetPage = Number.isInteger(page) && page >= 1 && page <= MAX_DEEP_LINK_PAGE ? page : 1
  const endAt = typeof route.query.to === 'string' ? Date.parse(route.query.to) : NaN
  void search(targetPage, Number.isFinite(endAt) ? endAt : Date.now(), 'replace')
}

watch(() => route.query, () => {
  if (route.name !== 'search' || routeKey(route.query) === syncedRouteKey) return
  readRoute()
  closeEvent()
  restoreSearch()
})

function eventSortValue(event: SearchEvent, prop: string): string {
  if (prop === 'timestamp' || prop === 'source' || prop === 'host' || prop === 'severity' || prop === 'msg') {
    return String(event[prop] ?? '')
  }
  return String(event.fields?.[prop] ?? '')
}

const visibleEvents = computed(() => {
  const rows = [...(result.value?.events ?? [])]
  if (!eventSortProp.value || !eventSortOrder.value) return rows
  rows.sort((left, right) => {
    const ranks = ['INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
    const comparison = eventSortProp.value === 'severity'
      ? ranks.indexOf(left.severity) - ranks.indexOf(right.severity)
      : eventSortValue(left, eventSortProp.value).localeCompare(eventSortValue(right, eventSortProp.value), undefined, { numeric: true, sensitivity: 'base' })
    return eventSortOrder.value === 'ascending' ? comparison : -comparison
  })
  return rows
})

function onEventSortChange(change: { prop: string | null; order: string | null }): void {
  eventSortProp.value = change.prop || ''
  eventSortOrder.value = change.order === 'ascending' || change.order === 'descending' ? change.order : null
  syncUrl(currentPage.value)
}

function readSavedQueries(): void {
  try {
    localStorage.removeItem('socp.search.saved-queries')
    savedQueries.value = []
    selectedSavedQueryId.value = ''; savedQueryName.value = ''; saveDialogVisible.value = false
    const raw = savedQueryKey.value ? localStorage.getItem(savedQueryKey.value) : null
    if (raw) {
      const parsed: unknown = JSON.parse(raw)
      savedQueries.value = Array.isArray(parsed) ? parsed.filter(item => item && typeof item.id === 'string' && typeof item.name === 'string' && typeof item.query === 'string' && validTimeRanges.includes(item.range)).slice(0, 20) : []
    }
  } catch { savedQueries.value = [] }
}

function persistSavedQueries(): void {
  try { if (savedQueryKey.value) localStorage.setItem(savedQueryKey.value, JSON.stringify(savedQueries.value)) } catch { /* optional preference */ }
}

async function loadFields(): Promise<void> {
  fieldsLoading.value = true
  fieldsError.value = ''
  try { fieldDefs.value = await listFields() }
  catch (cause) { fieldsError.value = cause instanceof Error ? cause.message : String(cause) }
  finally { fieldsLoading.value = false }
}

function appendFilter(field: string, value?: string): void {
  query.value = appendSearchFilter(query.value, field, value)
  closeEvent()
  void nextTick(() => queryInput.value?.focus())
}

async function openEvent(event: SearchEvent): Promise<void> {
  selectedEvent.value = event
  relatedAlarms.value = []
  relatedAlarmsError.value = ''
  const token = ++eventLineageToken
  relatedAlarmsLoading.value = false
  if (!event.eventId?.trim()) return
  relatedAlarmsLoading.value = true
  try {
    const alarms = await listAlarmsByEvent(event.eventId)
    if (token === eventLineageToken) relatedAlarms.value = alarms
  } catch (cause) {
    if (token === eventLineageToken) relatedAlarmsError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    if (token === eventLineageToken) relatedAlarmsLoading.value = false
  }
}

function closeEvent(): void {
  eventLineageToken += 1
  selectedEvent.value = null
  relatedAlarms.value = []
  relatedAlarmsError.value = ''
  relatedAlarmsLoading.value = false
}

function formatRuleVersions(alarm: Alarm): string {
  const versions = alarm.detectionResult?.ruleVersions
  if (!versions) return ''
  return Object.entries(versions).map(([ruleId, version]) => `${ruleId}: ${version}`).join(' · ')
}

function formatResultPosition(alarm: Alarm): string {
  const position = alarm.detectionResult?.inputPosition
  if (!position || position.partition == null || position.offset == null) return ''
  return `${position.topic ? `${position.topic} · ` : ''}p${position.partition} @ ${position.offset}`
}

function openRelatedAlarm(alarmId: string): void {
  if (!alarmId) return
  void router.push({ name: 'alarms', query: { q: selectedEvent.value?.eventId || undefined, alarmId } })
}

function useAsDetectionSample(): void {
  if (!selectedEvent.value || !canWrite.value) return
  try {
    const sample = stageDetectionSample(selectedEvent.value)
    void router.push({ name: 'rule-new', query: { sample } })
  } catch (error) { ElMessage.error(String(error)) }
}

function saveQuery(): void {
  const name = savedQueryName.value.trim()
  const value = query.value.trim() || '*'
  if (!name) return
  const existing = savedQueries.value.find(item => item.name === name)
  if (existing) {
    existing.query = value
    existing.range = selectedTimeRange.value
    existing.from = customFrom.value; existing.to = customTo.value
  } else {
    savedQueries.value.unshift({ id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, name, query: value, range: selectedTimeRange.value, from: customFrom.value, to: customTo.value })
    savedQueries.value = savedQueries.value.slice(0, 20)
  }
  persistSavedQueries()
  savedQueryName.value = ''
  saveDialogVisible.value = false
  ElMessage.success(t('search.querySaved'))
}

function applySavedQuery(id: string): void {
  const saved = savedQueries.value.find(item => item.id === id)
  if (!saved) return
  query.value = saved.query
  selectedTimeRange.value = saved.range
  if (saved.from) customFrom.value = saved.from
  if (saved.to) customTo.value = saved.to
  selectedSavedQueryId.value = ''
  void search()
}

function removeSavedQuery(id: string): void {
  savedQueries.value = savedQueries.value.filter(item => item.id !== id)
  persistSavedQueries()
}

async function fetchSilently(page: number, pageCursor: string | null, previousResult: SearchResult | null, signal: AbortSignal): Promise<SearchResult> {
  const nextResult = await splSearch(activeQuery.value || buildScopedQuery(query.value, activeTimeRange.value), {
    cursor: pageCursor,
    limit: pageSize.value,
    timeline: page === 1,
    signal,
  })
  if (page > 1 && !nextResult.timeline?.length && previousResult?.timeline?.length) {
    nextResult.timeline = previousResult.timeline
    nextResult.timelineApproximate = previousResult.timelineApproximate
  }
  return nextResult
}

async function fetchPage(page: number, pageCursor: string | null): Promise<void> {
  const sequence = ++requestSequence
  requestController?.abort()
  const controller = new AbortController()
  requestController = controller
  loading.value = true
  error.value = ''
  try {
    const nextResult = await fetchSilently(page, pageCursor, result.value, controller.signal)
    if (sequence !== requestSequence || cancelled || route.name !== 'search') return
    result.value = nextResult
    currentPage.value = page
    pageCursors.value[page - 1] = pageCursor
    if (nextResult.nextCursor) pageCursors.value[page] = nextResult.nextCursor
    syncUrl(page)
  } catch (cause) {
    if (sequence !== requestSequence || cancelled) return
    if (page === 1) result.value = null
    error.value = `${t('search.failed')}${cause instanceof Error ? cause.message : String(cause)}`
  } finally {
    if (sequence === requestSequence && !cancelled) loading.value = false
  }
}

async function search(targetPage = 1, endAt = Date.now(), history: 'push' | 'replace' = 'push', rawQuery = query.value, range = selectedTimeRange.value, from = customFrom.value, to = customTo.value): Promise<void> {
  if (range === 'custom') {
    const start = utcInput(from)
    const end = utcInput(to)
    if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) { error.value = t('experience.invalidRange'); return }
    activeFromAt.value = start; endAt = end
  } else {
    const duration = timeRangeOptions.find(option => option.key === range)?.durationMs
    activeFromAt.value = duration ? endAt - duration : null
  }
  const sequence = ++requestSequence
  requestController?.abort()
  const controller = new AbortController()
  requestController = controller
  activeRawQuery.value = rawQuery.trim() || '*'
  activeEndAt.value = endAt
  activeTimeRange.value = range
  activeQuery.value = buildScopedQuery(activeRawQuery.value, activeTimeRange.value, activeEndAt.value)
  currentPage.value = 1
  pageCursors.value = [null]
  result.value = null
  error.value = ''
  loading.value = true
  await syncUrl(targetPage, history)
  // Cursor pagination cannot jump to an arbitrary page directly, so a shared
  // deep link replays a bounded chain of cursors. Every intermediate page stays
  // private: result and currentPage are landed once, at the terminal
  // state, and a failed or exhausted replay stops immediately instead of
  // continuing with a stale cursor while keeping the error visible.
  const cursors: Array<string | null> = [null]
  let landed: SearchResult | null = null
  let landedPage = 1
  for (let page = 1; page <= targetPage; page += 1) {
    if (requestSequence !== sequence || cancelled || route.name !== 'search') return
    try {
      landed = await fetchSilently(page, cursors[page - 1] ?? null, landed, controller.signal)
      landedPage = page
    } catch (cause) {
      if (requestSequence !== sequence || cancelled || route.name !== 'search') return
      error.value = `${t('search.failed')}${cause instanceof Error ? cause.message : String(cause)}`
      break
    }
    if (page >= targetPage) break
    const nextCursor = landed?.nextCursor ?? null
    cursors[page] = nextCursor
    if (!nextCursor) break
  }
  if (requestSequence !== sequence || cancelled || route.name !== 'search') return
  if (landed) {
    result.value = landed
    currentPage.value = landedPage
    pageCursors.value = cursors
    syncUrl(landedPage)
  } else {
    result.value = null
    syncUrl(1)
  }
  loading.value = false
}

async function previousPage(): Promise<void> {
  if (currentPage.value <= 1 || loading.value) return
  await fetchPage(currentPage.value - 1, pageCursors.value[currentPage.value - 2] ?? null)
}

async function nextPage(): Promise<void> {
  const nextCursor = result.value?.nextCursor
  if (!nextCursor || loading.value || currentPage.value >= maxBrowsePages.value) return
  pageCursors.value[currentPage.value] = nextCursor
  await fetchPage(currentPage.value + 1, nextCursor)
}

async function changePageSize(): Promise<void> { await search(1, activeEndAt.value, 'replace', activeRawQuery.value, activeTimeRange.value, activeFromAt.value === null ? '' : new Date(activeFromAt.value).toISOString(), new Date(activeEndAt.value).toISOString()) }
function runExample(example: string): void { query.value = example; void search() }
function setTimeRange(range: TimeRangeKey): void { selectedTimeRange.value = range; if (range !== 'custom') void search() }

function onQueryEnter(event: Event | KeyboardEvent): void {
  if (!(event instanceof KeyboardEvent) || event.isComposing || event.shiftKey) return
  event.preventDefault()
  void search()
}

/** Exporting runs a server-side scan, so a second click must not start another. */
const exporting = ref<'json' | 'csv' | ''>('')
async function exportCurrent(format: 'json' | 'csv'): Promise<void> {
  if (exporting.value) return
  exporting.value = format
  const scoped = activeQuery.value || buildScopedQuery(query.value, selectedTimeRange.value)
  try {
    await exportSearch(scoped, format)
    ElMessage.success(t('search.exportReady'))
  } catch (cause) {
    ElMessage.error(`${t('search.exportFailed')}${cause instanceof Error ? cause.message : String(cause)}`)
  } finally { exporting.value = '' }
}

const maxBrowsePages = computed(() => Math.ceil(MAX_BROWSE_ROWS / pageSize.value))
const pageCount = computed(() => Math.max(1, Math.min(
  Math.ceil((result.value?.total ?? 0) / pageSize.value),
  maxBrowsePages.value,
)))
const showPagination = computed(() => Boolean(result.value && (result.value.nextCursor || currentPage.value > 1)))
const browseLimitVisible = computed(() => Boolean(result.value && result.value.total > MAX_BROWSE_ROWS))

watch(savedQueryKey, readSavedQueries)
onMounted(() => {
  readSavedQueries()
  void loadFields()
  restoreSearch()
})
</script>

<template>
  <div class="page-pad view-enter">
    <el-button v-if="investigationReturn" @click="router.push(investigationReturn)">{{ t('analystJourney.returnToInvestigation') }}</el-button>
    <p v-if="route.query.draft" role="status">{{ t('analystJourney.reviewSuggestedSearch') }}</p>
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('search.title')" :description="t('search.description')" />
    <el-button size="small" :aria-expanded="fieldBrowserOpen" @click="fieldBrowserOpen = !fieldBrowserOpen">{{ t('experience.fields') }}</el-button>
    <div v-if="route.query.sourceId || route.query.eventId" class="search-result-hint">source_id: {{ route.query.sourceId || '—' }} · eventId: {{ route.query.eventId || '—' }}</div>
    <div class="search-workspace" :class="{ 'search-workspace--compact': !fieldBrowserOpen }">
      <aside v-if="fieldBrowserOpen" class="search-field-browser" :aria-label="t('search.fieldBrowser')">
        <div class="search-field-browser-head">
          <div><strong>{{ t('search.fieldBrowser') }}</strong><span>{{ t('search.fieldBrowserHint') }}</span></div>
          <span v-if="fieldsLoading" class="search-field-status">{{ t('common.loading') }}</span>
        </div>
        <el-input v-model="fieldKeyword" size="small" clearable :placeholder="t('search.fieldSearchPlaceholder')" />
        <div v-if="fieldsError" class="search-field-fallback">{{ t('search.fieldFallback') }}</div>
        <div v-if="visibleFields.length" class="search-field-list">
          <button v-for="field in visibleFields" :key="field.id || field.fieldName" type="button" class="search-field-item" @click="appendFilter(field.fieldName)">
            <span class="search-field-item-main"><b>{{ field.fieldName }}</b><small>{{ field.fieldLabel }}</small></span>
            <span class="search-field-type">{{ field.fieldType }}</span>
          </button>
        </div>
        <EmptyState v-else :title="t('search.noFields')" />
      </aside>

      <section class="search-main-column">
        <el-card shadow="never" class="search-toolbar">
          <div class="search-query-row">
            <el-input ref="queryInput" v-model="query" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" resize="none" :aria-label="t('search.queryLabel')" :placeholder="t('search.queryPlaceholder')" @keydown.enter="onQueryEnter" />
            <el-tooltip :content="t('search.queryLimitHint')" placement="top"><el-button type="primary" :loading="loading" @click="() => search()">{{ t('search.runQuery') }}</el-button></el-tooltip>

          </div>
          <div class="search-query-help">{{ t('search.queryKeyboardHint') }}</div>
          <div v-if="hasDraftChanges" class="search-result-hint" role="status">{{ t('search.draftHint') }}</div>
          <div class="search-time-filter" role="group" :aria-label="t('search.timeRange')">
            <span class="search-time-filter-label">{{ t('search.timeRange') }}</span>
            <div class="search-time-filter-buttons">
              <el-button v-for="option in timeRangeOptions" :key="option.key" size="small" :type="selectedTimeRange === option.key ? 'primary' : ''" :aria-pressed="selectedTimeRange === option.key" @click="setTimeRange(option.key)">{{ t(option.label) }}</el-button>
            </div>
            <span class="search-time-filter-applied">{{ t('search.timeRangeApplied', { range: activeTimeRangeLabel }) }}<span v-if="activeTimeRange !== 'all'"> · {{ t('search.rangeEndingAt', { time: new Date(activeEndAt).toISOString() }) }}</span></span>
          </div>
          <div v-if="selectedTimeRange === 'custom'" class="search-custom-range">
            <label>{{ t('experience.from') }}<input v-model="customFrom" type="datetime-local" step="0.001" /></label>
            <label>{{ t('experience.to') }}<input v-model="customTo" type="datetime-local" step="0.001" /></label>
          </div>
          <p class="search-result-hint" data-testid="applied-bounds">{{ activeBounds }}</p>
          <div class="search-secondary-controls">
            <details><summary>{{ t('experience.export') }}</summary>            <el-tooltip :content="t('search.exportLimitHint')" placement="top"><el-button size="small" :loading="exporting === 'json'" :disabled="!result || Boolean(exporting)" @click="exportCurrent('json')">{{ t('common.exportJson') }}</el-button></el-tooltip>
            <el-tooltip :content="t('search.exportLimitHint')" placement="top"><el-button size="small" :loading="exporting === 'csv'" :disabled="!result || Boolean(exporting)" @click="exportCurrent('csv')">{{ t('common.exportCsv') }}</el-button></el-tooltip></details>
            <details><summary>{{ t('experience.searchTools') }}</summary>          <div class="search-saved-row">
            <el-select v-model="selectedSavedQueryId" size="small" clearable :placeholder="t('search.savedQueries')" @change="applySavedQuery">
              <el-option v-for="saved in savedQueries" :key="saved.id" :label="saved.name" :value="saved.id" />
            </el-select>
            <el-button size="small" @click="saveDialogVisible = true">{{ t('search.saveQuery') }}</el-button>
            <button v-for="saved in savedQueries.slice(0, 5)" :key="`remove-${saved.id}`" type="button" class="search-saved-remove" :title="t('search.removeSavedQuery')" @click="removeSavedQuery(saved.id)">× {{ saved.name }}</button>
          </div>
          <div class="search-examples"><el-tag v-for="example in examples" :key="example" size="small" role="button" tabindex="0" :aria-label="example" @click="runExample(example)" @keydown.enter.space.prevent="runExample(example)">{{ example }}</el-tag></div></details>
          </div>
        </el-card>

        <el-alert v-if="error" :title="error" type="error" :closable="false" class="search-error" />

        <template v-if="result">
          <el-alert v-if="result.degraded" type="warning" :title="t('search.degradedTo', { source: sourceLabel(result.source) })" :description="result.degradationReason || t('search.localCacheOnly')" :closable="false" show-icon class="search-error" />
          <el-alert v-if="browseLimitVisible" type="info" :title="t('search.browseLimit')" :closable="false" show-icon class="search-error" />
          <el-card shadow="never" class="search-result-card">
            <template #header><div class="search-result-head"><span>{{ t('search.matchedEvents', { count: result.total }) }}</span><span class="search-result-meta">{{ result.source }} · {{ result.elapsedMs ?? 0 }} ms</span></div></template>
            <div v-if="timelineRows.length > 1" class="search-timeline">
              <div class="search-timeline-head"><span>{{ t('search.histogram') }}</span><span class="search-timeline-hint">{{ t('search.timeRangeApplied', { range: activeTimeRangeLabel }) }} · {{ result.timelineApproximate ? t('search.timelineLimited') : t('search.timelineHint') }}</span></div>
              <div class="search-timeline-chart" role="img" :aria-label="t('search.histogram')">
                <div v-for="row in timelineRows" :key="row.key" class="search-timeline-bar"><div class="search-timeline-track"><span class="search-timeline-value">{{ row.count }}</span><i :style="{ height: `${Math.max(8, (Number(row.count) / maxTimelineCount) * 100)}%` }" /></div><span class="search-timeline-label">{{ String(row.key).slice(0, 10) }}</span></div>
              </div>
            </div>
            <div v-if="result.events.length" class="search-result-hint">{{ t('search.eventDetailHint') }} · {{ t('search.currentPageSortHint') }}</div>
            <EmptyState v-if="!result.events.length" :title="t('search.noResults')" :description="t('search.noResultsHint')" />
            <el-table v-else class="search-events-table" :data="visibleEvents" :default-sort="eventSortProp && eventSortOrder ? { prop: eventSortProp, order: eventSortOrder } : undefined" size="small" border allow-drag-last-column max-height="560" @header-dragend="onHeaderDragEnd" @sort-change="onEventSortChange" @row-click="openEvent">
              <el-table-column prop="timestamp" column-key="timestamp" sortable="custom" :label="t('common.timestamp')" :width="columnWidth('timestamp', 150)"><template #default="{ row }">{{ row.timestamp.slice(0, 19).replace('T', ' ') }}</template></el-table-column>
              <el-table-column prop="source" column-key="source" sortable="custom" :label="t('common.source')" :width="columnWidth('source', 90)" />
              <el-table-column prop="host" column-key="host" sortable="custom" :label="t('common.host')" :width="columnWidth('host', 90)" />
              <el-table-column prop="severity" column-key="severity" sortable="custom" :label="t('common.severity')" :width="columnWidth('severity', 80)"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column prop="msg" column-key="msg" sortable="custom" :label="t('common.message')" :width="columnWidth('msg')" min-width="240" show-overflow-tooltip><template #default="{ row }"><RowActivate :aria-label="row.msg || t('common.message')" @activate="openEvent(row as SearchEvent)">{{ row.msg }}</RowActivate></template></el-table-column>
            </el-table>
            <div v-if="showPagination" class="search-pagination"><span class="search-page-summary">{{ t('common.pageSummary', { page: currentPage, total: pageCount }) }}</span><div class="search-page-controls"><span class="search-page-size-label">{{ t('common.pageSize') }}</span><el-select v-model="pageSize" size="small" style="width:92px" @change="changePageSize"><el-option v-for="size in pageSizes" :key="size" :label="String(size)" :value="size" /></el-select><el-button size="small" :disabled="currentPage <= 1 || loading" @click="previousPage">{{ t('common.previousPage') }}</el-button><el-button size="small" :disabled="!result.nextCursor || currentPage >= maxBrowsePages || loading" @click="nextPage">{{ t('common.nextPage') }}</el-button></div></div>
          </el-card>
          <el-card v-if="result.stat" shadow="never"><template #header>{{ result.stat.type === 'timechart' ? t('search.timeDistributionDaily') : t('search.statsSummary', { type: result.stat.type === 'top' ? 'Top' : t('search.count') }) }}</template><el-table :data="result.stat.rows" size="small" border><el-table-column prop="key" :label="t('search.statKey')" show-overflow-tooltip /><el-table-column prop="count" :label="t('search.count')" width="220"><template #default="{ row }"><div class="search-stat-row"><span>{{ row.count }}</span><span class="search-stat-track"><i :style="{ width: `${Math.min(100, (row.count / maxStatCount) * 100)}%` }" /></span></div></template></el-table-column></el-table></el-card>
        </template>
      </section>
    </div>

    <el-dialog v-model="saveDialogVisible" :title="t('search.saveQuery')" width="440px">
      <el-form label-position="top">
        <FormField :label="t('search.saveQueryLabel')" required :hint="t('search.saveQueryHint')">
          <el-input v-model="savedQueryName" autofocus :placeholder="t('search.saveQueryPlaceholder')" @keyup.enter="saveQuery" />
        </FormField>
      </el-form>
      <template #footer><el-button @click="saveDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :disabled="!savedQueryName.trim()" @click="saveQuery">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-drawer :model-value="Boolean(selectedEvent)" :title="t('search.eventDetails')" size="min(620px, 96vw)" @close="closeEvent" @closed="restoreEventFocus">
      <template v-if="selectedEvent">
        <div class="search-event-summary"><SevBadge :value="selectedEvent.severity" /><span class="mono">{{ selectedEvent.timestamp }}</span><span>{{ selectedEvent.host || t('time.notAvailable') }}</span></div>
        <div class="search-event-message">{{ selectedEvent.msg || t('time.notAvailable') }}</div>
        <div class="search-event-fields"><div v-for="(value, key) in selectedEvent.fields" :key="key" class="search-event-field"><div class="search-event-field-head"><span><b>{{ key }}</b><small>{{ availableFields.find(field => field.fieldName === key)?.fieldType || 'string' }}</small></span><el-button link type="primary" size="small" @click="appendFilter(key, String(value))">{{ t('search.filterValue') }}</el-button></div><code>{{ value }}</code></div></div>
        <div class="search-event-lineage">
          <div class="search-event-lineage-head"><div><strong>{{ t('search.relatedAlarms') }}</strong><span>{{ t('search.relatedAlarmsHint') }}</span></div><code class="mono">{{ t('search.eventId') }}: {{ selectedEvent.eventId }}</code></div>
          <el-alert v-if="relatedAlarmsError" :title="t('search.relatedAlarmsFailed')" :description="relatedAlarmsError" type="error" :closable="false" show-icon />
          <div v-else-if="relatedAlarmsLoading" class="search-event-lineage-loading">{{ t('search.relatedAlarmsLoading') }}</div>
          <div v-else-if="!relatedAlarms.length" class="search-event-lineage-empty">{{ t('search.noRelatedAlarms') }}</div>
          <div v-else class="search-event-lineage-list">
            <div v-for="alarm in relatedAlarms" :key="alarm.id" class="search-event-lineage-item">
              <div class="search-event-lineage-item-main"><SevBadge :value="alarm.severity" /><div><strong>{{ alarm.title || alarm.ruleName || alarm.ruleId }}</strong><small class="mono">{{ alarm.id }} · {{ alarm.status || 'OPEN' }}</small></div></div>
              <el-button link type="primary" size="small" @click="openRelatedAlarm(alarm.id)">{{ t('search.openRelatedAlarm') }}</el-button>
              <div v-if="formatRuleVersions(alarm)" class="search-event-lineage-meta"><span>{{ t('search.ruleVersions') }}</span><code>{{ formatRuleVersions(alarm) }}</code></div>
              <div v-if="formatResultPosition(alarm)" class="search-event-lineage-meta"><span>{{ t('search.resultPosition') }}</span><code>{{ formatResultPosition(alarm) }}</code></div>
            </div>
          </div>
        </div>
        <el-button v-if="canWrite" type="primary" plain @click="useAsDetectionSample">{{ t('workflow.detectionSample') }}</el-button>
        <details class="search-event-raw" open><summary>{{ t('search.rawEvent') }}</summary><pre>{{ JSON.stringify(selectedEvent, null, 2) }}</pre></details>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.search-workspace--compact { grid-template-columns: minmax(0, 1fr); }
.search-custom-range, .search-secondary-controls { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 10px; }
.search-custom-range label { display: flex; flex-direction: column; gap: 4px; color: var(--ns-text-2); font-size: 12px; }
.search-custom-range input { padding: 7px; border: 1px solid var(--ns-border); background: var(--ns-surface); color: var(--ns-text); border-radius: 5px; }
.search-secondary-controls summary { cursor: pointer; color: var(--ns-text-2); padding: 6px 0; font-size: 12px; }
.search-query-row { grid-template-columns: minmax(0, 1fr) auto; }
@media (max-width: 720px) { .search-query-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; } }
</style>
