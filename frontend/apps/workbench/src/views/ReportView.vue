<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import EmptyState from '../components/EmptyState.vue'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { loadEcharts } from '../lib/echarts'
import { useRequest } from '../composables/useRequest'
import PageHeader from '../components/PageHeader.vue'
import { archiveReport, dailyReport, downloadArchivedReport, listArchive, trend7d, type ReportSummary, type ReportTrend } from '../api'
import { useI18n } from '../composables/useI18n'
import { sevColor } from '../lib/ui'
import { useWriteAccess } from '../composables/useWriteAccess'
import { tOr } from '../utils/i18nLabel'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const { t, n, locale } = useI18n()
const canWrite = useWriteAccess()

const summaryRequest = useRequest<ReportSummary>()
const trendRequest = useRequest<ReportTrend>()
const archiveRequest = useRequest<Awaited<ReturnType<typeof listArchive>>>()
const report = summaryRequest.data
const trend = trendRequest.data
const archiveInfo = archiveRequest.data
const reportLoading = computed(() => summaryRequest.loading.value || trendRequest.loading.value || archiveRequest.loading.value)
const summaryLoading = summaryRequest.loading
const trendLoading = trendRequest.loading
const archiveLoading = archiveRequest.loading
const reportError = summaryRequest.error
const trendError = trendRequest.error
const archiveError = archiveRequest.error
const archiveBusy = ref(false)
const generatedArchiveKey = ref('')
const archiveDate = ref('')
const archiveRoot = ref('')
const chartError = ref('')
const downloadKey = ref('')
const downloadRequest = useRequest<{ key: string; url: string }>()
let pendingPopup: Window | null = null
let disposed = false
const chartBar = shallowRef<ECharts>()
const chartLine = shallowRef<ECharts>()
const barEl = ref<HTMLElement>()
const lineEl = ref<HTMLElement>()
let renderToken = 0
const severityKeys = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const

function severityLabel(severity: string): string {
  const key = 'severities.' + severity
  const translated = t(key)
  return translated === key ? severity : translated
}

/** Backend report-source enum; unknown or missing values keep the raw token. */
function sourceLabel(source: string | null | undefined): string {
  return tOr(t, `report.sources.${source ?? 'unspecified'}`, source ?? '—')
}

function tc(light: string, dark: string): string { return props.theme === 'dark' ? dark : light }

async function loadSummary() {
  if (disposed) return
  if (await summaryRequest.execute(signal => dailyReport({ signal }))) {
    await nextTick()
    await renderCharts()
  }
}

async function loadTrend() {
  if (disposed) return
  if (await trendRequest.execute(signal => trend7d({ signal }))) {
    await nextTick()
    await renderCharts()
  }
}

async function loadReport() {
  await Promise.all([loadSummary(), loadTrend(), loadArchive()])
}

async function renderCharts() {
  if (disposed || (!report.value && !trend.value)) return
  const token = ++renderToken
  try {
    const echarts = await loadEcharts()
    if (disposed || token !== renderToken) return
    chartError.value = ''
    if (barEl.value && report.value) {
      chartBar.value ??= echarts.init(barEl.value, 'socp')
      chartBar.value.setOption({
        tooltip: {},
        xAxis: { type: 'category', axisLabel: { color: tc('#1f2328', '#e6edf3') }, data: severityKeys.map(severityLabel) }, yAxis: { type: 'value' },
        series: [{ type: 'bar', data: severityKeys.map(k => report.value?.bySeverity[k] ?? 0),
          itemStyle: { color: (p: { dataIndex: number }) => sevColor(severityKeys[p.dataIndex]) } }],
      })
    }
    if (lineEl.value && trend.value) {
      chartLine.value ??= echarts.init(lineEl.value, 'socp')
      chartLine.value.setOption({
        tooltip: { trigger: 'axis' },
        xAxis: { type: 'category', axisLabel: { color: tc('#1f2328', '#e6edf3') }, data: trend.value.days }, yAxis: { type: 'value' },
        series: [{ type: 'line', smooth: true, data: trend.value.counts, areaStyle: {} }],
      })
    }
  } catch (error) {
    if (!disposed && token === renderToken) chartError.value = error instanceof Error ? error.message : String(error)
  }
}

async function loadArchive() {
  if (disposed) return
  const prefix = archiveRoot.value && archiveDate.value ? `${archiveRoot.value}${archiveDate.value.replaceAll('-', '')}/` : 'reports/'
  const result = await archiveRequest.execute(signal => listArchive(prefix, { signal }))
  if (result && !archiveRoot.value) archiveRoot.value = result.prefix
}

watch(archiveDate, () => { archiveRequest.reset(); void loadArchive() })

async function doArchive() {
  if (disposed || !canWrite.value || archiveBusy.value) return
  archiveBusy.value = true
  try {
    const result = await archiveReport()
    if (disposed) return
    if (result.archived) {
      generatedArchiveKey.value = result.archiveKey || ''
      ElMessage.success(t('report.saveSuccess', { day: result.day }))
      const generatedDate = result.day && /^\d{8}$/.test(result.day) ? `${result.day.slice(0, 4)}-${result.day.slice(4, 6)}-${result.day.slice(6)}` : ''
      if (generatedDate && !archiveRoot.value) {
        await loadArchive()
        if (disposed || !archiveRoot.value) return
      }
      if (archiveRoot.value && generatedDate && archiveDate.value !== generatedDate) {
        archiveDate.value = generatedDate
        return
      }
    } else ElMessage.error(t('report.archiveFailed'))
    await loadArchive()
  } catch (error) {
    if (!disposed) ElMessage.error((error as Error).message || t('report.archiveFailed'))
  } finally {
    if (!disposed) archiveBusy.value = false
  }
}

async function downloadArchive(key: string) {
  if (disposed || downloadKey.value) return
  // Open within the click gesture; opening after an awaited URL is blocked by
  // common popup policies. Detach the opener before loading any remote URL.
  const popup = window.open('about:blank', '_blank')
  if (!popup) { ElMessage.error(t('report.popupBlocked')); return }
  popup.opener = null
  pendingPopup = popup
  downloadKey.value = key
  const result = await downloadRequest.execute(signal => downloadArchivedReport(key, { signal }))
  if (disposed) { popup.close(); return }
  try {
    if (!result) throw downloadRequest.error.value || new Error(t('report.archiveDownloadFailed'))
    const url = new URL(result.url)
    if (result.key !== key || !['https:', 'http:'].includes(url.protocol)) throw new Error(t('report.archiveUrlEmpty'))
    popup.location.replace(url.href)
  } catch (error) {
    popup.close()
    ElMessage.error((error as Error).message || t('report.archiveDownloadFailed'))
  } finally { pendingPopup = null; downloadKey.value = '' }
}

function reportSize(size: unknown): string {
  return typeof size === 'number' && Number.isFinite(size) && size >= 0 ? `${n(size / 1024, 'decimal')} KiB` : '—'
}

function reportDate(key: string): string {
  const day = key?.split('/').at(-2)
  return day && /^\d{8}$/.test(day) ? `${day.slice(0, 4)}-${day.slice(4, 6)}-${day.slice(6)}` : '—'
}

function reportName(key: string): string {
  const name = key.split('/').pop() || key
  return name.replace(/\.json$/i, '')
}

function onResize() {
  chartBar.value?.resize()
  chartLine.value?.resize()
}

watch(() => props.theme, () => { void nextTick(renderCharts) })
watch(locale, () => { void nextTick(renderCharts) })
onMounted(() => {
  loadReport()
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  disposed = true
  summaryRequest.cancel()
  trendRequest.cancel()
  archiveRequest.cancel()
  downloadRequest.cancel()
  pendingPopup?.close()
  renderToken++
  window.removeEventListener('resize', onResize)
  chartBar.value?.dispose()
  chartLine.value?.dispose()
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('report.title')" :description="t('report.description')">
      <template #actions><el-button size="small" :loading="reportLoading" @click="loadReport">{{ t('common.refresh') }}</el-button><el-button v-if="canWrite" type="primary" size="small" :loading="archiveBusy" @click="doArchive">{{ t('report.generateReport') }}</el-button></template>
    </PageHeader>
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('report.readOnly') }}</div>
    <el-alert v-if="archiveError" :title="archiveError.message" type="error" :closable="false" />
    <div class="report-toolbar-meta">
      <span v-if="archiveInfo" style="font-size:12px;color:var(--ns-text-3)">{{ t('report.archivedObjects', { count: archiveInfo.count }) }}</span>
    </div>
    <div v-if="reportError" class="report-source-error" role="alert">
      <span>{{ t('report.dailyLoadFailed') }} {{ reportError.message }}</span>
      <el-button link :loading="summaryLoading" @click="loadSummary">{{ t('common.retry') }}</el-button>
    </div>
    <div v-if="chartError" class="report-source-error" role="alert"><span>{{ chartError }}</span><el-button link @click="renderCharts">{{ t('common.retry') }}</el-button></div>
    <el-alert v-if="report?.degraded" type="warning" :title="t('report.degradedTitle', { source: sourceLabel(report.source) })"
      :description="report.degradationReason || t('report.degradedDescription')" show-icon :closable="false" style="margin-bottom:12px" />
    <el-row class="metrics-row" :gutter="12" style="margin-bottom:14px" v-if="report">
      <el-col :xs="24" :sm="12" :md="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ report.total }}</div><div class="label">{{ t('report.todayAlarms') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-danger)">{{ report.bySeverity.CRITICAL ?? 0 }}</div><div class="label">{{ severityLabel('CRITICAL') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-danger)">{{ report.bySeverity.HIGH ?? 0 }}</div><div class="label">{{ severityLabel('HIGH') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-warning)">{{ report.bySeverity.MEDIUM ?? 0 }}</div><div class="label">{{ severityLabel('MEDIUM') }}</div></div></el-card></el-col>
    </el-row>
    <el-row :gutter="12" class="report-charts">
      <el-col :xs="24" :md="12"><el-card shadow="never">
        <template #header>{{ t('report.alarmSeverityDistribution') }}</template>
        <EmptyState v-if="!report" :title="summaryLoading ? t('common.loading') : t('common.empty')" />
        <div ref="barEl" v-show="report" style="height:300px" />
      </el-card></el-col>
      <el-col :xs="24" :md="12"><el-card shadow="never">
        <template #header>{{ t('report.sevenDayTrend') }}</template>
        <div v-if="trendError" class="report-source-error" role="alert"><span>{{ t('report.trendLoadFailed') }} {{ trendError.message }}</span><el-button link :loading="trendLoading" @click="loadTrend">{{ t('common.retry') }}</el-button></div>
        <el-alert v-if="trend?.degraded" type="warning" :title="t('report.degradedTitle', { source: sourceLabel(trend.source) })" :description="trend.degradationReason || t('report.degradedDescription')" :closable="false" />
        <EmptyState v-if="!trend" :title="trendLoading ? t('common.loading') : t('common.empty')" />
        <div ref="lineEl" v-show="trend" style="height:300px" />
      </el-card></el-col>
    </el-row>
    <el-card shadow="never" style="margin-top:14px" v-if="report" class="report-rules-card">
      <template #header>{{ t('report.topRules') }}</template>
      <el-table :data="report.byRule" size="small" border><el-table-column prop="rule" :label="t('common.rule')" show-overflow-tooltip /><el-table-column prop="count" :label="t('report.alarmCount')" width="120" /></el-table>
    </el-card>
    <el-card shadow="never" style="margin-top:14px" class="report-storage-card">
      <template #header><div class="report-storage-head"><strong>{{ t('report.savedReports') }}</strong><span>{{ t('report.storageHint') }}</span></div></template>
      <div v-if="generatedArchiveKey" class="report-generated" role="status"><span>{{ t('report.generatedSnapshot', { day: reportDate(generatedArchiveKey) }) }}</span><el-button type="primary" plain :loading="downloadKey === generatedArchiveKey" :disabled="Boolean(downloadKey)" @click="downloadArchive(generatedArchiveKey)">{{ t('report.downloadGenerated') }}</el-button></div>
      <div class="report-archive-filter"><el-input v-model="archiveDate" type="date" :disabled="!archiveRoot || archiveBusy" :placeholder="t('report.allDates')" :aria-label="t('report.archiveDate')" clearable /><el-button :loading="archiveLoading" @click="loadArchive">{{ t('common.refresh') }}</el-button></div>
      <el-alert v-if="archiveInfo?.truncated" type="warning" :title="t('report.archiveTruncated', { limit: archiveInfo.limit })" :closable="false" />
      <EmptyState v-if="!archiveInfo?.objects.length" :title="archiveLoading ? t('common.loading') : t('common.empty')" />
      <el-table v-if="archiveInfo?.objects.length" :data="archiveInfo.objects" size="small" border>
        <el-table-column :label="t('report.reportFile')" min-width="240" show-overflow-tooltip><template #default="{ row }"><strong>{{ reportName(row.key) }}</strong><span class="report-file-date">{{ reportDate(row.key) }}</span><details class="report-storage-details"><summary>{{ t('report.storageDetails') }}</summary><span class="mono">{{ row.key }}</span></details></template></el-table-column>
        <el-table-column prop="size" :label="t('report.size')" width="120"><template #default="{ row }">{{ reportSize(row.size) }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" size="small" :loading="downloadKey === row.key" :disabled="Boolean(downloadKey)" @click="downloadArchive(row.key)">{{ t('report.download') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
