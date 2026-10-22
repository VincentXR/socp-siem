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
import { useRouter } from 'vue-router'
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { loadEcharts } from '../lib/echarts'
import { useRequest } from '../composables/useRequest'
import PageHeader from '../components/PageHeader.vue'
import MetricCard from '../components/MetricCard.vue'
import { archiveReport, dailyReport, downloadArchivedReport, listArchive, trend7d, type ReportSummary, type ReportTrend } from '../api'
import { useI18n } from '../composables/useI18n'
import { sevColor } from '../lib/ui'
import { useWriteAccess } from '../composables/useWriteAccess'
import { tOr } from '../utils/i18nLabel'
import { ALARM_STATUS_ALL } from '../app/alarm-statuses'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const { t, n, d, locale } = useI18n()
function formatTime(value: unknown): string {
  if (!value) return '—'
  try { return d(String(value), 'dateTime') } catch { return String(value) }
}
const canWrite = useWriteAccess()
const router = useRouter()
function openRuleAlarms(ruleId: string) {
  if (!report.value || report.value.queryWindow !== 'today') return
  void router.push({ name: 'alarms', query: { status: ALARM_STATUS_ALL, rule: ruleId, from: report.value.date + 'T00:00:00Z', to: report.value.generatedAt } })
}

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
const archiveName = ref('')
const archiveObjects = computed(() => (archiveInfo.value?.objects || []).filter(item => item.key.toLowerCase().includes(archiveName.value.trim().toLowerCase())))
function openArchive() { document.getElementById('report-snapshots')?.scrollIntoView({ block: 'start' }) }
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
  <div class="page-pad view-enter report-view">
    <PageHeader :eyebrow="t('experience.coverage')" :title="t('report.title')" :description="t('report.description')">
      <template #actions><el-button :loading="reportLoading" @click="loadReport">{{ t('common.refresh') }}</el-button><el-button @click="openArchive">{{ t('experience.snapshots') }}</el-button><el-button v-if="canWrite" type="primary" :loading="archiveBusy" :disabled="!report || Boolean(reportError)" @click="doArchive">{{ t('experience.saveSnapshot') }}</el-button></template>
    </PageHeader>
    <div class="report-context">
      <div class="report-context-heading"><span class="report-context-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M7 3h7l4 4v14H6V3h1Z"/><path d="M14 3v5h4M9 12h6M9 16h6"/></svg></span><div><span class="report-context-label">{{ t('experience.snapshotScope') }}</span><p>{{ report?.date || '—' }} <span class="report-context-source">{{ sourceLabel(report?.source) }}</span></p></div></div>
      <p v-if="report" class="report-snapshot-context">{{ t('common.timestamp') }}: {{ formatTime(report.generatedAt) }} · {{ t('experience.lastObserved') }}: {{ formatTime(report.freshness) }}</p>
      <details v-if="report" class="report-preview"><summary>{{ t('experience.snapshotPreview') }}</summary><p>{{ report.date }} · {{ report.total }} {{ t('report.todayAlarms') }}</p><dl class="report-preview-severities"><div v-for="level in severityKeys" :key="level"><dt>{{ severityLabel(level) }}</dt><dd>{{ n(report.bySeverity[level] || 0, 'integer') }}</dd></div></dl><details class="report-raw-preview"><summary>{{ t('report.rawSnapshot') }}</summary><pre class="snapshot-json">{{ JSON.stringify(report, null, 2) }}</pre></details></details>
    </div>
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
    <div class="page-metrics metrics-row report-metrics" v-if="report">
      <MetricCard :label="t('report.todayAlarms')" tone="info">{{ n(report.total, 'integer') }}</MetricCard>
      <MetricCard :label="severityLabel('CRITICAL')" tone="danger">{{ n(report.bySeverity.CRITICAL ?? 0, 'integer') }}</MetricCard>
      <MetricCard :label="severityLabel('HIGH')" tone="warning">{{ n(report.bySeverity.HIGH ?? 0, 'integer') }}</MetricCard>
      <MetricCard :label="severityLabel('MEDIUM')" tone="neutral">{{ n(report.bySeverity.MEDIUM ?? 0, 'integer') }}</MetricCard>
    </div>
    <el-row :gutter="12" class="report-charts">
      <el-col :xs="24" :md="12"><el-card shadow="never">
        <template #header>{{ t('report.alarmSeverityDistribution') }}</template>
        <EmptyState v-if="!report" :title="t(summaryLoading ? 'common.loading' : reportError ? 'experience.unknownData' : 'common.empty')" />
        <div ref="barEl" v-show="report" style="height:300px" />
      </el-card></el-col>
      <el-col :xs="24" :md="12"><el-card shadow="never">
        <template #header>{{ t('report.sevenDayTrend') }}</template>
        <div v-if="trendError" class="report-source-error" role="alert"><span>{{ t('report.trendLoadFailed') }} {{ trendError.message }}</span><el-button link :loading="trendLoading" @click="loadTrend">{{ t('common.retry') }}</el-button></div>
        <el-alert v-if="trend?.degraded" type="warning" :title="t('report.degradedTitle', { source: sourceLabel(trend.source) })" :description="trend.degradationReason || t('report.degradedDescription')" :closable="false" />
        <EmptyState v-if="!trend" :title="t(trendLoading ? 'common.loading' : trendError ? 'experience.unknownData' : 'common.empty')" />
        <div ref="lineEl" v-show="trend" style="height:300px" />
      </el-card></el-col>
    </el-row>
    <el-card shadow="never" style="margin-top:14px" v-if="report" class="report-rules-card">
      <template #header>{{ t('report.topRules') }}</template>
      <el-table :data="report.byRule" size="small" border><el-table-column prop="rule" :label="t('common.rule')" show-overflow-tooltip><template #default="{ row }"><el-button v-if="row.ruleId && report.queryWindow === 'today'" link type="primary" @click="openRuleAlarms(row.ruleId)">{{ row.rule }}</el-button><span v-else>{{ row.rule }}</span></template></el-table-column><el-table-column prop="count" :label="t('report.alarmCount')" width="120" /></el-table>
    </el-card>
    <el-card shadow="never" style="margin-top:14px" id="report-snapshots" class="report-storage-card">
      <template #header><div class="report-storage-head"><strong>{{ t('experience.snapshots') }}</strong><span>{{ t('report.storageHint') }}</span></div></template>
      <div v-if="generatedArchiveKey" class="report-generated" role="status"><span>{{ t('report.generatedSnapshot', { day: reportDate(generatedArchiveKey) }) }}</span><el-button type="primary" plain :loading="downloadKey === generatedArchiveKey" :disabled="Boolean(downloadKey)" @click="downloadArchive(generatedArchiveKey)">{{ t('experience.downloadJson') }}</el-button></div>
      <div class="report-archive-filter"><el-input v-model="archiveName" clearable :placeholder="t('experience.snapshotName')" :aria-label="t('experience.snapshotName')" /><el-input v-model="archiveDate" type="date" :disabled="!archiveRoot || archiveBusy" :placeholder="t('report.allDates')" :aria-label="t('report.archiveDate')" clearable /><el-button :loading="archiveLoading" @click="loadArchive">{{ t('common.refresh') }}</el-button></div>
      <el-alert v-if="archiveInfo?.truncated" type="warning" :title="t('report.archiveTruncated', { limit: archiveInfo.limit })" :closable="false" />
      <EmptyState v-if="!archiveInfo?.objects.length" :title="t(archiveLoading ? 'common.loading' : archiveError ? 'experience.unknownData' : 'common.empty')" />
      <el-table v-if="archiveInfo?.objects.length" :data="archiveObjects" size="small" border :empty-text="t('experience.noMatches')">
        <el-table-column :label="t('report.reportFile')" min-width="240" show-overflow-tooltip><template #default="{ row }"><strong>{{ reportName(row.key) }}</strong><span class="report-file-date">{{ reportDate(row.key) }}</span><details class="report-storage-details"><summary>{{ t('report.storageDetails') }}</summary><span class="mono">{{ row.key }}</span></details></template></el-table-column>
        <el-table-column prop="size" :label="t('report.size')" width="120"><template #default="{ row }">{{ reportSize(row.size) }}</template></el-table-column>
        <el-table-column :label="t('common.actions')" width="144" fixed="right"><template #default="{ row }"><el-button link type="primary" size="small" :loading="downloadKey === row.key" :disabled="Boolean(downloadKey)" @click="downloadArchive(row.key)">{{ t('experience.downloadJson') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.snapshot-json { max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; padding: 12px; border-radius: var(--ns-radius-sm); background: var(--ns-bg-subtle); font-size: 12px; }
.report-context { margin-bottom: 16px; padding: 18px 20px; border: 1px solid var(--ns-border); border-radius: var(--ns-radius-md); background: linear-gradient(110deg, color-mix(in srgb, var(--ns-accent) 5%, var(--ns-surface)), var(--ns-surface) 65%); }
.report-context-heading { display: flex; gap: 12px; align-items: center; }
.report-context-heading > div { min-width: 0; }
.report-context-heading p { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin: 4px 0 0; font-size: 19px; font-weight: 650; font-variant-numeric: tabular-nums; }
.report-context-icon { display: grid; place-items: center; flex: 0 0 42px; height: 42px; border: 1px solid color-mix(in srgb, var(--ns-accent) 18%, var(--ns-border)); border-radius: 12px; color: var(--ns-accent-fg); background: var(--ns-surface); }
.report-context-icon svg { width: 24px; height: 24px; }
.report-context-label { color: var(--ns-text-2); font-size: 12px; line-height: 1.5; }
.report-context-source { padding: 2px 8px; color: var(--ns-accent-fg); background: color-mix(in srgb, var(--ns-accent) 8%, transparent); border-radius: 6px; font-size: 12px; font-weight: 500; }
.report-context > p { margin: 0; font-size: 14px; line-height: 1.7; }
.report-context > p.report-snapshot-context { margin-top: 4px; font-size: 12px; }
.report-snapshot-context { color: var(--ns-text-2); font-size: 12px; }
.report-preview { margin-top: 12px; border-top: 1px solid var(--ns-border); padding-top: 10px; font-size: 13px; }
.report-preview summary { cursor: pointer; color: var(--ns-accent-fg); }
.report-preview-severities { display: flex; flex-wrap: wrap; gap: 8px 24px; margin: 12px 0; }
.report-preview-severities > div { min-width: 72px; }
.report-preview-severities dt { color: var(--ns-text-3); font-size: 12px; }
.report-preview-severities dd { margin: 4px 0 0; font-weight: 650; font-variant-numeric: tabular-nums; }
.report-raw-preview { color: var(--ns-text-2); font-size: 12px; }
.report-raw-preview summary { color: var(--ns-text-3); }
.report-archive-filter :deep(.el-input) { max-width: 300px; }
.report-storage-head { align-items: baseline; flex-wrap: wrap; }
.report-storage-head span { font-size: 12px; line-height: 1.5; }
.report-metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); margin-bottom: 16px; }
.report-toolbar-meta:empty { display: none; }
.report-preview summary:focus-visible { outline: 2px solid var(--ns-accent); outline-offset: 4px; border-radius: 3px; }
@media (max-width: 720px) { .report-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .report-context { padding: 16px; } }
</style>
