<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import { loadEcharts } from '../lib/echarts'
import { useRequest } from '../composables/useRequest'
import { archiveReport, dailyReport, downloadArchivedReport, listArchive, trend7d, type ReportSummary } from '../api'

const props = defineProps<{ theme: 'light' | 'dark' }>()

const report = ref<ReportSummary | null>(null)
const trend = ref<{ days: string[]; counts: number[] } | null>(null)
const reportRequest = useRequest<{ summary: ReportSummary; dailyTrend: { days: string[]; counts: number[] } }>()
const reportLoading = reportRequest.loading
const reportError = reportRequest.error
const archiveInfo = ref<Awaited<ReturnType<typeof listArchive>> | null>(null)
const archiveBusy = ref(false)
const chartBar = shallowRef<ECharts>()
const chartLine = shallowRef<ECharts>()
const barEl = ref<HTMLElement>()
const lineEl = ref<HTMLElement>()
let renderToken = 0

function tc(light: string, dark: string): string { return props.theme === 'dark' ? dark : light }

async function loadReport() {
  const result = await reportRequest.execute(async signal => {
    const requestOptions = { signal }
    const [summary, dailyTrend] = await Promise.all([dailyReport(requestOptions), trend7d(requestOptions)])
    return { summary, dailyTrend }
  })
  if (!result) return
  report.value = result.summary
  trend.value = result.dailyTrend
  await nextTick()
  await renderCharts()
}

async function renderCharts() {
  const token = ++renderToken
  const echarts = await loadEcharts()
  if (token !== renderToken) return
  if (barEl.value && report.value) {
    chartBar.value?.dispose()
    chartBar.value = echarts.init(barEl.value, 'socp')
    chartBar.value.setOption({
      title: { text: '告警级别分布', textStyle: { fontSize: 14, color: tc('#1f2328', '#e6edf3') } }, tooltip: {},
      xAxis: { type: 'category', data: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] }, yAxis: { type: 'value' },
      series: [{ type: 'bar', data: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(k => report.value?.bySeverity[k] ?? 0),
        itemStyle: { color: (p: { dataIndex: number }) => ['#f56c6c', '#e63946', '#e6a23c', '#909399'][p.dataIndex] } }],
    })
  }
  if (lineEl.value && trend.value) {
    chartLine.value?.dispose()
    chartLine.value = echarts.init(lineEl.value, 'socp')
    chartLine.value.setOption({
      title: { text: '近 7 日趋势', textStyle: { fontSize: 14, color: tc('#1f2328', '#e6edf3') } }, tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: trend.value.days }, yAxis: { type: 'value' },
      series: [{ type: 'line', smooth: true, data: trend.value.counts, areaStyle: {} }],
    })
  }
}

async function loadArchive() {
  try { archiveInfo.value = await listArchive() } catch { /* MinIO 未启用时静默 */ }
}

async function doArchive() {
  if (archiveBusy.value) return
  archiveBusy.value = true
  try {
    const result = await archiveReport()
    if (result.archived) {
      ElMessage.success(`报表已归档至 MinIO（${result.day}/${result.dailyKey}）`)
    } else {
      ElMessage.error(result.error || '归档失败')
    }
    await loadArchive()
  } catch (e) {
    ElMessage.error((e as Error).message || '归档失败')
  } finally {
    archiveBusy.value = false
  }
}

async function downloadArchive(key: string) {
  try {
    const result = await downloadArchivedReport(key)
    if (!result.url) throw new Error('归档下载地址为空')
    window.open(result.url, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error((error as Error).message || '归档下载失败')
  }
}

function onResize() {
  chartBar.value?.resize()
  chartLine.value?.resize()
}

watch(() => props.theme, () => { void nextTick(renderCharts) })
onMounted(() => {
  loadReport()
  loadArchive()
  window.addEventListener('resize', onResize)
})
onUnmounted(() => {
  renderToken++
  window.removeEventListener('resize', onResize)
  chartBar.value?.dispose()
  chartLine.value?.dispose()
})
</script>

<template>
  <div class="page-pad view-enter">
    <div style="margin-bottom:12px;display:flex;gap:10px;align-items:center">
      <el-button :loading="reportLoading" @click="loadReport">刷新</el-button>
      <el-button type="primary" :loading="archiveBusy" @click="doArchive">归档至 MinIO</el-button>
      <span v-if="archiveInfo" style="font-size:12px;color:var(--ns-text-3)">已归档 {{ archiveInfo.count }} 个对象</span>
    </div>
    <el-alert v-if="reportError" type="error" title="报告加载失败，请重试" show-icon closable @close="reportRequest.reset" />
    <el-row :gutter="12" style="margin-bottom:14px" v-if="report">
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ report.total }}</div><div class="label">今日告警</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ report.bySeverity.CRITICAL ?? 0 }}</div><div class="label">CRITICAL</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e63946">{{ report.bySeverity.HIGH ?? 0 }}</div><div class="label">HIGH</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ report.bySeverity.MEDIUM ?? 0 }}</div><div class="label">MEDIUM</div></div></el-card></el-col>
    </el-row>
    <el-row :gutter="12">
      <el-col :span="12"><el-card shadow="never"><div ref="barEl" style="height:300px" /></el-card></el-col>
      <el-col :span="12"><el-card shadow="never"><div ref="lineEl" style="height:300px" /></el-card></el-col>
    </el-row>
    <el-card shadow="never" style="margin-top:14px" v-if="report">
      <template #header>TOP 规则</template>
      <el-table :data="report.byRule" size="small" border><el-table-column prop="rule" label="规则" show-overflow-tooltip /><el-table-column prop="count" label="告警数" width="120" /></el-table>
    </el-card>
    <el-card shadow="never" style="margin-top:14px" v-if="archiveInfo?.objects.length">
      <template #header>MinIO 归档对象</template>
      <el-table :data="archiveInfo.objects" size="small" border>
        <el-table-column prop="key" label="对象 Key" min-width="240" show-overflow-tooltip />
        <el-table-column prop="size" label="大小" width="120"><template #default="{ row }">{{ (row.size / 1024).toFixed(1) }} KB</template></el-table-column>
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" size="small" @click="downloadArchive(row.key)">下载</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
