<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
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
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import ElTooltip from 'element-plus/es/components/tooltip/index.mjs'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import EmptyState from '../components/EmptyState.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { exportSearch, listFields, splSearch, type FieldDef, type SearchEvent, type SearchResult } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
type TimeRangeKey = '15m' | '30m' | '1h' | '6h' | '24h' | 'all'

const pendingQuery = typeof window === 'undefined' ? null : window.sessionStorage.getItem('socp.search.query')
const routeQuery = typeof route.query.q === 'string' ? route.query.q : ''
const routeRange = typeof route.query.range === 'string' ? route.query.range : ''
const validTimeRanges: TimeRangeKey[] = ['15m', '30m', '1h', '6h', '24h', 'all']
const query = ref(routeQuery || pendingQuery || '*')
const result = ref<SearchResult | null>(null)
const loading = ref(false)
const error = ref('')
const currentPage = ref(1)
const pageSize = ref(50)
const pageCursors = ref<Array<string | null>>([null])
const pageSizes = [25, 50, 100]
const MAX_BROWSE_ROWS = 10_000
const timeRangeOptions: Array<{ key: TimeRangeKey; label: string; durationMs?: number }> = [
  { key: '15m', label: 'search.timeRanges.last15Minutes', durationMs: 15 * 60_000 },
  { key: '30m', label: 'search.timeRanges.last30Minutes', durationMs: 30 * 60_000 },
  { key: '1h', label: 'search.timeRanges.lastHour', durationMs: 60 * 60_000 },
  { key: '6h', label: 'search.timeRanges.last6Hours', durationMs: 6 * 60 * 60_000 },
  { key: '24h', label: 'search.timeRanges.last24Hours', durationMs: 24 * 60 * 60_000 },
  { key: 'all', label: 'search.timeRanges.all' },
]
const selectedTimeRange = ref<TimeRangeKey>(validTimeRanges.includes(routeRange as TimeRangeKey) ? routeRange as TimeRangeKey : '30m')
const activeTimeRange = ref<TimeRangeKey>(selectedTimeRange.value)
const activeQuery = ref('')
const fieldDefs = ref<FieldDef[]>([])
const fieldKeyword = ref('')
const fieldsLoading = ref(false)
const fieldsError = ref('')
const selectedEvent = ref<SearchEvent | null>(null)
const savedQueries = ref<Array<{ id: string; name: string; query: string; range: TimeRangeKey }>>([])
const selectedSavedQueryId = ref('')
const saveDialogVisible = ref(false)
const savedQueryName = ref('')
const SAVED_QUERY_KEY = 'socp.search.saved-queries'
let requestSequence = 0
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

function splitPipeline(rawQuery: string): { filter: string; pipeline: string } {
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

function buildScopedQuery(rawQuery: string, range: TimeRangeKey, endAt = Date.now()): string {
  const normalized = rawQuery.trim() || '*'
  const option = timeRangeOptions.find(candidate => candidate.key === range)
  if (!option?.durationMs) return normalized
  const { filter, pipeline } = splitPipeline(normalized)
  const from = new Date(endAt - option.durationMs).toISOString()
  const to = new Date(endAt).toISOString()
  const scopedFilter = `(${filter.trim() || '*'}) AND timestamp>=${from} AND timestamp<=${to}`
  return pipeline ? `${scopedFilter} ${pipeline}` : scopedFilter
}

const activeTimeRangeLabel = computed(() => {
  const option = timeRangeOptions.find(candidate => candidate.key === activeTimeRange.value) ?? timeRangeOptions[1]
  return t(option.label)
})

function syncUrl(page = 1): void {
  void router.replace({ query: { ...route.query, q: query.value.trim() || '*', range: selectedTimeRange.value, page: page > 1 ? String(page) : undefined } })
}

function readSavedQueries(): void {
  try {
    const raw = localStorage.getItem(SAVED_QUERY_KEY)
    if (raw) savedQueries.value = JSON.parse(raw) as Array<{ id: string; name: string; query: string; range: TimeRangeKey }>
  } catch { savedQueries.value = [] }
}

function persistSavedQueries(): void {
  try { localStorage.setItem(SAVED_QUERY_KEY, JSON.stringify(savedQueries.value)) } catch { /* optional preference */ }
}

async function loadFields(): Promise<void> {
  fieldsLoading.value = true
  fieldsError.value = ''
  try { fieldDefs.value = await listFields() }
  catch (cause) { fieldsError.value = cause instanceof Error ? cause.message : String(cause) }
  finally { fieldsLoading.value = false }
}

function appendFilter(field: string, value?: string): void {
  const normalizedField = field.trim()
  if (!normalizedField) return
  const clause = value === undefined ? `${normalizedField}=` : `${normalizedField}="${value.replaceAll('"', '\\"')}"`
  const current = query.value.trim() || '*'
  const { filter, pipeline } = splitPipeline(current)
  const base = filter.trim() === '*' ? '' : filter.trim()
  query.value = `${base ? `${base} AND ` : ''}${clause}${pipeline ? ` ${pipeline}` : ''}`.trim()
  syncUrl()
}

function openEvent(event: SearchEvent): void { selectedEvent.value = event }

function saveQuery(): void {
  const name = savedQueryName.value.trim()
  const value = query.value.trim() || '*'
  if (!name) return
  const existing = savedQueries.value.find(item => item.name === name)
  if (existing) {
    existing.query = value
    existing.range = selectedTimeRange.value
  } else {
    savedQueries.value.unshift({ id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`, name, query: value, range: selectedTimeRange.value })
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
  selectedSavedQueryId.value = ''
  void search()
}

function removeSavedQuery(id: string): void {
  savedQueries.value = savedQueries.value.filter(item => item.id !== id)
  persistSavedQueries()
}

async function fetchPage(page: number, pageCursor: string | null): Promise<void> {
  const sequence = requestSequence
  loading.value = true
  error.value = ''
  try {
    const previousResult = result.value
    const nextResult = await splSearch(activeQuery.value || buildScopedQuery(query.value, activeTimeRange.value), {
      cursor: pageCursor,
      limit: pageSize.value,
      timeline: page === 1,
    })
    if (sequence !== requestSequence) return
    if (page > 1 && !nextResult.timeline?.length && previousResult?.timeline?.length) {
      nextResult.timeline = previousResult.timeline
      nextResult.timelineApproximate = previousResult.timelineApproximate
    }
    result.value = nextResult
    currentPage.value = page
    pageCursors.value[page - 1] = pageCursor
    if (result.value.nextCursor) pageCursors.value[page] = result.value.nextCursor
    syncUrl(page)
  } catch (cause) {
    if (sequence !== requestSequence) return
    if (page === 1) result.value = null
    error.value = `${t('search.failed')}${cause instanceof Error ? cause.message : String(cause)}`
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function search(): Promise<void> {
  requestSequence += 1
  activeTimeRange.value = selectedTimeRange.value
  activeQuery.value = buildScopedQuery(query.value, activeTimeRange.value)
  currentPage.value = 1
  pageCursors.value = [null]
  result.value = null
  syncUrl()
  await fetchPage(1, null)
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

async function changePageSize(): Promise<void> { await search() }
function runExample(example: string): void { query.value = example; void search() }
function setTimeRange(range: TimeRangeKey): void { selectedTimeRange.value = range; void search() }

async function exportCurrent(format: 'json' | 'csv'): Promise<void> {
  const scoped = activeQuery.value || buildScopedQuery(query.value, selectedTimeRange.value)
  try {
    await exportSearch(scoped, format)
    ElMessage.success(t('search.exportReady'))
  } catch (cause) {
    ElMessage.error(`${t('search.exportFailed')}${cause instanceof Error ? cause.message : String(cause)}`)
  }
}

const maxBrowsePages = computed(() => Math.ceil(MAX_BROWSE_ROWS / pageSize.value))
const pageCount = computed(() => Math.max(1, Math.min(
  Math.ceil((result.value?.total ?? 0) / pageSize.value),
  maxBrowsePages.value,
)))
const showPagination = computed(() => Boolean(result.value && (result.value.nextCursor || currentPage.value > 1)))
const browseLimitVisible = computed(() => Boolean(result.value && result.value.total > MAX_BROWSE_ROWS))

onMounted(() => {
  if (pendingQuery) window.sessionStorage.removeItem('socp.search.query')
  readSavedQueries()
  void Promise.allSettled([loadFields(), search()])
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.alarmsAndEvents')" :title="t('search.title')" :description="t('search.description')" />
    <div class="search-workspace">
      <aside class="search-field-browser" :aria-label="t('search.fieldBrowser')">
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
            <el-input v-model="query" :placeholder="t('search.queryPlaceholder')" clearable @keyup.enter="search" />
            <el-tooltip :content="t('search.queryLimitHint')" placement="top"><el-button type="primary" :loading="loading" @click="search">{{ t('search.runQuery') }}</el-button></el-tooltip>
            <el-tooltip :content="t('search.exportLimitHint')" placement="top"><el-button size="small" :disabled="!result" @click="exportCurrent('json')">{{ t('common.exportJson') }}</el-button></el-tooltip>
            <el-tooltip :content="t('search.exportLimitHint')" placement="top"><el-button size="small" :disabled="!result" @click="exportCurrent('csv')">{{ t('common.exportCsv') }}</el-button></el-tooltip>
          </div>
          <div class="search-saved-row">
            <el-select v-model="selectedSavedQueryId" size="small" clearable :placeholder="t('search.savedQueries')" @change="applySavedQuery">
              <el-option v-for="saved in savedQueries" :key="saved.id" :label="saved.name" :value="saved.id" />
            </el-select>
            <el-button size="small" @click="saveDialogVisible = true">{{ t('search.saveQuery') }}</el-button>
            <button v-for="saved in savedQueries.slice(0, 5)" :key="`remove-${saved.id}`" type="button" class="search-saved-remove" :title="t('search.removeSavedQuery')" @click="removeSavedQuery(saved.id)">× {{ saved.name }}</button>
          </div>
          <div class="search-time-filter" role="group" :aria-label="t('search.timeRange')">
            <span class="search-time-filter-label">{{ t('search.timeRange') }}</span>
            <div class="search-time-filter-buttons">
              <el-button v-for="option in timeRangeOptions" :key="option.key" size="small" :type="selectedTimeRange === option.key ? 'primary' : ''" :aria-pressed="selectedTimeRange === option.key" @click="setTimeRange(option.key)">{{ t(option.label) }}</el-button>
            </div>
            <span class="search-time-filter-applied">{{ t('search.timeRangeApplied', { range: activeTimeRangeLabel }) }}</span>
          </div>
          <div class="search-examples"><el-tag v-for="example in examples" :key="example" size="small" @click="runExample(example)">{{ example }}</el-tag></div>
        </el-card>

        <el-alert v-if="error" :title="error" type="error" :closable="false" class="search-error" />

        <template v-if="result">
          <el-alert v-if="result.degraded" type="warning" :title="t('search.degradedTo', { source: result.source })" :description="result.degradationReason || t('search.localCacheOnly')" :closable="false" show-icon class="search-error" />
          <el-alert v-if="browseLimitVisible" type="info" :title="t('search.browseLimit')" :closable="false" show-icon class="search-error" />
          <el-card shadow="never" class="search-result-card">
            <template #header><div class="search-result-head"><span>{{ t('search.matchedEvents', { count: result.total }) }}</span><span class="search-result-meta">{{ result.source }} · {{ result.elapsedMs ?? 0 }} ms</span></div></template>
            <div v-if="timelineRows.length" class="search-timeline">
              <div class="search-timeline-head"><span>{{ t('search.histogram') }}</span><span class="search-timeline-hint">{{ t('search.timeRangeApplied', { range: activeTimeRangeLabel }) }} · {{ result.timelineApproximate ? t('search.timelineLimited') : t('search.timelineHint') }}</span></div>
              <div class="search-timeline-chart" role="img" :aria-label="t('search.histogram')">
                <div v-for="row in timelineRows" :key="row.key" class="search-timeline-bar"><div class="search-timeline-track"><span class="search-timeline-value">{{ row.count }}</span><i :style="{ height: `${Math.max(8, (Number(row.count) / maxTimelineCount) * 100)}%` }" /></div><span class="search-timeline-label">{{ String(row.key).slice(0, 10) }}</span></div>
              </div>
            </div>
            <div v-if="result.events.length" class="search-result-hint">{{ t('search.eventDetailHint') }} · {{ t('search.currentPageSortHint') }}</div>
            <EmptyState v-if="!result.events.length" :title="t('search.noResults')" :description="t('search.noResultsHint')" />
            <el-table v-else class="search-events-table" :data="result.events" size="small" border allow-drag-last-column max-height="560" @header-dragend="onHeaderDragEnd" @row-click="openEvent">
              <el-table-column prop="timestamp" column-key="timestamp" :label="t('common.timestamp')" :width="columnWidth('timestamp', 150)"><template #default="{ row }">{{ row.timestamp.slice(0, 19).replace('T', ' ') }}</template></el-table-column>
              <el-table-column prop="source" column-key="source" :label="t('common.source')" :width="columnWidth('source', 90)" />
              <el-table-column prop="host" column-key="host" :label="t('common.host')" :width="columnWidth('host', 90)" />
              <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 80)"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column prop="msg" column-key="msg" :label="t('common.message')" :width="columnWidth('msg')" min-width="240" show-overflow-tooltip />
            </el-table>
            <div v-if="showPagination" class="search-pagination"><span class="search-page-summary">{{ t('common.pageSummary', { page: currentPage, total: pageCount }) }}</span><div class="search-page-controls"><span class="search-page-size-label">{{ t('common.pageSize') }}</span><el-select v-model="pageSize" size="small" style="width:92px" @change="changePageSize"><el-option v-for="size in pageSizes" :key="size" :label="String(size)" :value="size" /></el-select><el-button size="small" :disabled="currentPage <= 1 || loading" @click="previousPage">{{ t('common.previousPage') }}</el-button><el-button size="small" :disabled="!result.nextCursor || currentPage >= maxBrowsePages || loading" @click="nextPage">{{ t('common.nextPage') }}</el-button></div></div>
          </el-card>
          <el-card v-if="result.stat" shadow="never"><template #header>{{ result.stat.type === 'timechart' ? t('search.timeDistributionDaily') : t('search.statsSummary', { type: result.stat.type === 'top' ? 'Top' : t('search.count') }) }}</template><el-table :data="result.stat.rows" size="small" border><el-table-column prop="key" :label="t('search.statKey')" show-overflow-tooltip /><el-table-column prop="count" :label="t('search.count')" width="220"><template #default="{ row }"><div class="search-stat-row"><span>{{ row.count }}</span><span class="search-stat-track"><i :style="{ width: `${Math.min(100, (row.count / maxStatCount) * 100)}%` }" /></span></div></template></el-table-column></el-table></el-card>
        </template>
      </section>
    </div>

    <el-dialog v-model="saveDialogVisible" :title="t('search.saveQuery')" width="420px"><el-input v-model="savedQueryName" autofocus :placeholder="t('search.saveQueryPlaceholder')" @keyup.enter="saveQuery" /><template #footer><el-button @click="saveDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :disabled="!savedQueryName.trim()" @click="saveQuery">{{ t('common.save') }}</el-button></template></el-dialog>

    <el-drawer :model-value="Boolean(selectedEvent)" :title="t('search.eventDetails')" size="620px" @close="selectedEvent = null">
      <template v-if="selectedEvent">
        <div class="search-event-summary"><SevBadge :value="selectedEvent.severity" /><span class="mono">{{ selectedEvent.timestamp }}</span><span>{{ selectedEvent.host || t('time.notAvailable') }}</span></div>
        <div class="search-event-message">{{ selectedEvent.msg || t('time.notAvailable') }}</div>
        <div class="search-event-fields"><div v-for="(value, key) in selectedEvent.fields" :key="key" class="search-event-field"><div class="search-event-field-head"><span><b>{{ key }}</b><small>{{ availableFields.find(field => field.fieldName === key)?.fieldType || 'string' }}</small></span><el-button link type="primary" size="small" @click="appendFilter(key, String(value))">{{ t('search.filterValue') }}</el-button></div><code>{{ value }}</code></div></div>
        <details class="search-event-raw" open><summary>{{ t('search.rawEvent') }}</summary><pre>{{ JSON.stringify(selectedEvent, null, 2) }}</pre></details>
      </template>
    </el-drawer>
  </div>
</template>
