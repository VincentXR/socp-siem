<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { exportSearch, splSearch, type SearchResult } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const pendingQuery = typeof window === 'undefined' ? null : window.sessionStorage.getItem('socp.search.query')
const query = ref(pendingQuery || 'source=auth severity=HIGH')
const result = ref<SearchResult | null>(null)
const loading = ref(false)
const error = ref('')
const currentPage = ref(1)
const pageSize = ref(50)
const pageCursors = ref<Array<string | null>>([null])
const pageSizes = [25, 50, 100]
const examples = [
  'source=auth severity=HIGH',
  'msg contains "blocked" | top src_ip 5',
  'severity>=HIGH | timechart',
  'src_ip=10.0.0.9 OR user=admin',
  'source=web | count by http_method',
  'bytes>=1000 | head 10',
]
const maxStatCount = computed(() => Math.max(1, ...(result.value?.stat?.rows.map(row => Number(row.count)) ?? [1])))
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('search-events')

async function fetchPage(page: number, pageCursor: string | null) {
  loading.value = true
  error.value = ''
  try {
    result.value = await splSearch(query.value, { cursor: pageCursor, limit: pageSize.value })
    currentPage.value = page
    pageCursors.value[page - 1] = pageCursor
    if (result.value.nextCursor) pageCursors.value[page] = result.value.nextCursor
  } catch (err) {
    if (page === 1) result.value = null
    error.value = `${t('search.failed')}${err instanceof Error ? err.message : String(err)}`
  } finally {
    loading.value = false
  }
}

async function search() {
  currentPage.value = 1
  pageCursors.value = [null]
  result.value = null
  await fetchPage(1, null)
}

async function previousPage() {
  if (currentPage.value <= 1 || loading.value) return
  await fetchPage(currentPage.value - 1, pageCursors.value[currentPage.value - 2] ?? null)
}

async function nextPage() {
  const nextCursor = result.value?.nextCursor
  if (!nextCursor || loading.value) return
  pageCursors.value[currentPage.value] = nextCursor
  await fetchPage(currentPage.value + 1, nextCursor)
}

async function changePageSize() {
  await search()
}

function runExample(example: string) {
  query.value = example
  search()
}

onMounted(() => {
  if (pendingQuery) window.sessionStorage.removeItem('socp.search.query')
  search()
})

const pageCount = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / pageSize.value)))
const showPagination = computed(() => Boolean(result.value && (result.value.nextCursor || currentPage.value > 1)))
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('search.title')" :description="t('search.description')" />
    <el-card shadow="never" class="search-toolbar">
      <div class="search-query-row">
        <el-input v-model="query" :placeholder="t('search.queryPlaceholder')" clearable @keyup.enter="search" />
        <el-button type="primary" :loading="loading" @click="search">{{ t('search.runQuery') }}</el-button>
        <el-button size="small" @click="exportSearch(query, 'json')">{{ t('common.exportJson') }}</el-button>
        <el-button size="small" @click="exportSearch(query, 'csv')">{{ t('common.exportCsv') }}</el-button>
      </div>
      <div class="search-examples">
        <el-tag v-for="example in examples" :key="example" size="small" @click="runExample(example)">{{ example }}</el-tag>
      </div>
    </el-card>

    <el-alert v-if="error" :title="error" type="error" :closable="false" class="search-error" />

    <template v-if="result">
      <el-alert v-if="result.degraded" type="warning"
        :title="t('search.degradedTo', { source: result.source })"
        :description="result.degradationReason || (t('search.localCacheOnly'))"
        :closable="false" show-icon class="search-error" />
      <el-card shadow="never" class="search-result-card">
        <template #header>
          <div class="search-result-head">
          <span>{{ t('search.matchedEvents', { count: result.total }) }}</span>
          <span class="search-result-meta">
            {{ result.source }} · {{ result.elapsedMs ?? 0 }} ms
          </span>
          </div>
        </template>
        <el-table class="search-events-table" :data="result.events" size="small" border allow-drag-last-column max-height="560" @header-dragend="onHeaderDragEnd">
          <el-table-column prop="timestamp" column-key="timestamp" :label="t('common.timestamp')" :width="columnWidth('timestamp', 150)" sortable><template #default="{ row }">{{ row.timestamp.slice(0, 19).replace('T', ' ') }}</template></el-table-column>
          <el-table-column prop="source" column-key="source" :label="t('common.source')" :width="columnWidth('source', 90)" sortable />
          <el-table-column prop="host" column-key="host" :label="t('common.host')" :width="columnWidth('host', 90)" sortable />
          <el-table-column prop="severity" column-key="severity" :label="t('common.severity')" :width="columnWidth('severity', 80)" sortable><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
          <el-table-column prop="msg" column-key="msg" :label="t('common.message')" :width="columnWidth('msg')" min-width="240" sortable show-overflow-tooltip />
        </el-table>
        <div v-if="showPagination" class="search-pagination">
          <span class="search-page-summary">{{ t('common.pageSummary', { page: currentPage, total: pageCount }) }}</span>
          <div class="search-page-controls">
            <span class="search-page-size-label">{{ t('common.pageSize') }}</span>
            <el-select v-model="pageSize" size="small" style="width:92px" @change="changePageSize">
              <el-option v-for="size in pageSizes" :key="size" :label="String(size)" :value="size" />
            </el-select>
            <el-button size="small" :disabled="currentPage <= 1 || loading" @click="previousPage">{{ t('common.previousPage') }}</el-button>
            <el-button size="small" :disabled="!result.nextCursor || loading" @click="nextPage">{{ t('common.nextPage') }}</el-button>
          </div>
        </div>
      </el-card>
      <el-card v-if="result.stat" shadow="never">
        <template #header>{{ result.stat.type === 'timechart' ? t('search.timeDistributionDaily') : t('search.statsSummary', { type: result.stat.type === 'top' ? 'Top' : t('search.count') }) }}</template>
        <el-table :data="result.stat.rows" size="small" border>
          <el-table-column prop="key" label="Key" sortable show-overflow-tooltip />
          <el-table-column prop="count" :label="t('search.count')" width="220" sortable>
            <template #default="{ row }">
              <div class="search-stat-row"><span>{{ row.count }}</span><span class="search-stat-track"><i :style="{ width: `${Math.min(100, (row.count / maxStatCount) * 100)}%` }" /></span></div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>
