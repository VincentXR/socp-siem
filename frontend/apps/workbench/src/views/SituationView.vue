<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/progress/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElProgress from 'element-plus/es/components/progress/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { useQuery } from '@tanstack/vue-query'
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'
import TrendChart from '../components/TrendChart.vue'
import SevBadge from '../components/SevBadge.vue'
import {
  alarmStats, currentSession, gasEngineStats, gasRecentAlerts, ingestSummary, isAbortError, SEVERITIES,
  type ApiRequestOptions,
  type AlarmStats, type GasAlert, type GasStats, type IngestSummary,
} from '../api'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'session-expired': [] }>()

const { t, d, locale } = useI18n()

const liveFeed = ref<Array<GasAlert & { _new?: boolean }>>([])
const liveOn = ref(true)
const liveSevFilter = ref('')
const epsHistory = ref<number[]>([])
let alertStream: EventSource | null = null
const gaugeEl = ref<HTMLElement>()
const donutEl = ref<HTMLElement>()
const epsEl = ref<HTMLElement>()
const chartGauge = shallowRef<ECharts>()
const chartDonut = shallowRef<ECharts>()
const chartEps = shallowRef<ECharts>()
let renderToken = 0

const situationQuery = useQuery({
  queryKey: ['situation', 'snapshot'],
  queryFn: async ({ signal }) => {
    const options: ApiRequestOptions = { signal }
    const [stats, engine, recent, ingest] = await Promise.allSettled([
      alarmStats(options), gasEngineStats(options), gasRecentAlerts(options), ingestSummary(options),
    ])
    for (const result of [stats, engine, recent, ingest]) {
      if (result.status === 'rejected' && isAbortError(result.reason)) throw result.reason
    }
    return {
      stats: stats.status === 'fulfilled' ? stats.value : null,
      engine: engine.status === 'fulfilled' ? engine.value : null,
      recent: recent.status === 'fulfilled' ? recent.value : [],
      ingest: ingest.status === 'fulfilled' ? ingest.value : null,
    }
  },
  refetchInterval: () => liveOn.value ? 4_000 : false,
  refetchIntervalInBackground: false,
})
const sitStats = computed<AlarmStats | null>(() => situationQuery.data.value?.stats ?? null)
const sitEngine = computed<GasStats | null>(() => situationQuery.data.value?.engine ?? null)
const sitIngest = computed<IngestSummary | null>(() => situationQuery.data.value?.ingest ?? null)

function sevColor(severity: string) {
  return { CRITICAL: '#fb7185', HIGH: '#ef4444', MEDIUM: '#f59e0b', LOW: '#94a3b8', INFO: '#64748b' }[severity] ?? '#64748b'
}
function tc(light: string, dark: string) { return props.theme === 'dark' ? dark : light }
const feedView = computed(() => liveSevFilter.value ? liveFeed.value.filter(alert => alert.severity === liveSevFilter.value) : liveFeed.value)
const queuePct = computed(() => Math.round((sitEngine.value?.queueLoad ?? 0) * 1000) / 10)
const queueColor = computed(() => queuePct.value > 70 ? '#fb7185' : queuePct.value > 30 ? '#f59e0b' : '#22c55e')

function openAlertStream() {
  try {
    alertStream = new EventSource('/detect-web/api/v1/stream')
    alertStream.addEventListener('alert', (event: MessageEvent) => {
      try {
        const value = JSON.parse(event.data)
        if (value && value.ruleId) {
          mergeFeed([{
            id: value.id ?? `sse-${value.ruleId}-${value.timestamp}`,
            timestamp: value.timestamp ?? new Date().toISOString(),
            ruleId: value.ruleId, ruleName: value.ruleName ?? '', severity: value.severity ?? 'INFO',
            message: value.message ?? '', entity: value.entity ?? '',
          }])
          void loadSituation()
        }
      } catch { /* 忽略异常帧 */ }
    })
    alertStream.onerror = () => {
      setTimeout(async () => {
        try {
          await currentSession()
        } catch {
          emit('session-expired')
        }
      }, 600)
    }
  } catch { /* 不支持 SSE 时退化为轮询 */ }
}
function closeAlertStream() {
  if (alertStream) { alertStream.close(); alertStream = null }
}
async function loadSituation() { await situationQuery.refetch() }
function mergeFeed(incoming: GasAlert[]) {
  const known = new Set(liveFeed.value.map(alert => alert.id))
  const fresh = incoming.filter(alert => !known.has(alert.id)).map(alert => ({ ...alert, _new: true }))
  if (!fresh.length) return
  liveFeed.value = [...fresh, ...liveFeed.value.map(alert => ({ ...alert, _new: false }))].slice(0, 200)
  window.setTimeout(() => { liveFeed.value = liveFeed.value.map(alert => ({ ...alert, _new: false })) }, 1600)
}
function renderSitCharts() {
  const token = ++renderToken
  setTimeout(async () => {
    const echarts = await loadEcharts()
    if (token !== renderToken) return
    const stats = sitStats.value
    if (gaugeEl.value) {
      if (!chartGauge.value || chartGauge.value.isDisposed()) chartGauge.value = echarts.init(gaugeEl.value, 'socp')
      chartGauge.value.setOption({
        series: [{ type: 'gauge', min: 0, max: 100, radius: '92%', center: ['50%', '58%'], startAngle: 210, endAngle: -30, splitNumber: 5,
          axisLine: { lineStyle: { width: 14, color: [[0.2, '#67c23a'], [0.4, '#95d475'], [0.65, '#e6a23c'], [0.85, '#f89898'], [1, '#f56c6c']] } },
          pointer: { width: 4, length: '62%' }, axisTick: { distance: -14, length: 4, lineStyle: { color: 'transparent' } },
          splitLine: { distance: -14, length: 14, lineStyle: { color: 'transparent', width: 2 } },
          axisLabel: { distance: 16, fontSize: 10, color: tc('#818b98', '#9198a1') },
          detail: { valueAnimation: true, fontSize: 26, fontWeight: 700, offsetCenter: [0, '38%'], formatter: '{value}', color: tc('#1f2328', '#e6edf3') },
          title: { offsetCenter: [0, '72%'], fontSize: 12, color: tc('#59636e', '#9198a1') }, data: [{ value: stats?.avgRisk ?? 0, name: t('situation.avgThreatScore') }] }],
      })
    }
    if (donutEl.value) {
      if (!chartDonut.value || chartDonut.value.isDisposed()) chartDonut.value = echarts.init(donutEl.value, 'socp')
      const levels = stats?.byRiskLevel ?? {}
      chartDonut.value.setOption({ tooltip: { trigger: 'item' }, legend: { bottom: 0, itemWidth: 8, itemHeight: 8, textStyle: { fontSize: 11 } }, series: [{ type: 'pie', radius: ['48%', '72%'], center: ['50%', '44%'], avoidLabelOverlap: true, itemStyle: { borderRadius: 4, borderColor: 'transparent', borderWidth: 0 }, label: { show: false }, labelLine: { show: false }, data: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(level => ({ name: level, value: levels[level] ?? 0, itemStyle: { color: sevColor(level) } })).filter(item => item.value > 0) }] })
    }
    if (epsEl.value) {
      if (!chartEps.value || chartEps.value.isDisposed()) chartEps.value = echarts.init(epsEl.value, 'socp')
      chartEps.value.setOption({ grid: { left: 34, right: 10, top: 18, bottom: 20 }, tooltip: { trigger: 'axis' }, xAxis: { type: 'category', show: false, data: epsHistory.value.map((_, index) => index) }, yAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } }, series: [{ type: 'line', smooth: true, showSymbol: false, data: epsHistory.value, lineStyle: { color: '#67c23a', width: 2 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(103,194,58,.35)' }, { offset: 1, color: 'rgba(103,194,58,.02)' }]) } }] })
    }
  }, 80)
}
function toggleLive() {
  liveOn.value = !liveOn.value
  if (liveOn.value) {
    if (document.visibilityState === 'visible') openAlertStream()
    void loadSituation()
  } else {
    closeAlertStream()
  }
}
function onResize() { chartGauge.value?.resize(); chartDonut.value?.resize(); chartEps.value?.resize() }
function onVisibilityChange() {
  if (document.visibilityState === 'hidden') closeAlertStream()
  else if (liveOn.value) { openAlertStream(); void loadSituation() }
}

watch(() => props.theme, renderSitCharts)
watch(locale, renderSitCharts)
watch(() => situationQuery.data.value, snapshot => {
  if (!snapshot) return
  mergeFeed(snapshot.recent)
  if (snapshot.ingest) epsHistory.value = [...epsHistory.value, snapshot.ingest.eps1m ?? 0].slice(-40)
  renderSitCharts()
})
onMounted(() => {
  if (liveOn.value) openAlertStream()
  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onUnmounted(() => {
  renderToken++
  closeAlertStream(); window.removeEventListener('resize', onResize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  chartGauge.value?.dispose(); chartDonut.value?.dispose(); chartEps.value?.dispose()
})
</script>

<template>
        <!-- 实时态势大屏 -->
  <div class="page-pad view-enter sit-wrap">
          <!-- KPI 条 -->
          <div class="sit-kpis">
            <div class="sit-kpi">
              <div class="k-num">{{ sitEngine?.eventCount ?? 0 }}</div><div class="k-label">{{ t('situation.engineEvents') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#f56c6c">{{ sitEngine?.alertCount ?? 0 }}</div><div class="k-label">{{ t('situation.ruleAlerts') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#e6a23c">{{ sitEngine?.suppressedCount ?? 0 }}</div><div class="k-label">{{ t('situation.suppressedDedup') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" :style="{ color: (sitEngine?.dropCount ?? 0) > 0 ? '#f56c6c' : '#67c23a' }">{{ sitEngine?.dropCount ?? 0 }}</div>
              <div class="k-label">{{ t('situation.backpressureDrops') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:#409eff">{{ sitIngest?.eps1m ?? 0 }}</div><div class="k-label">{{ t('situation.ingestEps') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num">{{ queuePct }}%</div>
              <div class="k-label">{{ t('situation.queueLevel') }}</div>
              <el-progress :percentage="Math.min(100, queuePct)" :show-text="false" :stroke-width="4"
                :color="queuePct > 70 ? '#f56c6c' : queuePct > 30 ? '#e6a23c' : '#67c23a'" style="margin-top:4px" />
            </div>
          </div>

          <el-row :gutter="12" style="margin-bottom:12px">
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.threatScore') }}（0–100）</template>
                <div ref="gaugeEl" style="height:180px"></div>
                <div style="text-align:center;font-size:12px;color:#909399">
                  {{ t('situation.sevenDayAlarms') }} <b style="color:#303133">{{ sitStats?.total ?? 0 }}</b>
                  · {{ t('situation.highRisk') }} <b style="color:#f56c6c">{{ (sitStats?.byRiskLevel?.CRITICAL ?? 0) + (sitStats?.byRiskLevel?.HIGH ?? 0) }}</b>
                </div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.sevenDayRiskDistribution') }}</template>
                <div ref="donutEl" style="height:210px"></div>
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.sevenDayTrend') }}</template>
                <TrendChart :data="sitStats?.trend7d" variant="situation" style="height:210px" />
              </el-card>
            </el-col>
            <el-col :span="6">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.ingestThroughput') }}（EPS）</template>
                <div ref="epsEl" style="height:210px"></div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :span="13">
              <el-card shadow="never" class="sit-card">
                <template #header>
                  <div style="display:flex;align-items:center;gap:10px">
                    <span class="live-dot" :class="{ off: !liveOn }" />
                    <span>{{ t('situation.liveEventStream') }}</span>
                    <el-select v-model="liveSevFilter" :placeholder="t('situation.allLevels')" clearable size="small" style="width:120px">
                      <el-option v-for="s in SEVERITIES" :key="s" :label="t('severities.' + s) || s" :value="s" />
                    </el-select>
                    <el-button size="small" @click="toggleLive">{{ liveOn ? t('situation.pause') : t('situation.resume') }}</el-button>
                    <el-button size="small" @click="loadSituation">{{ t('common.refresh') }}</el-button>
                    <span style="margin-left:auto;font-size:12px;color:#909399">{{ t('situation.eventCount', { count: feedView.length }) }}</span>
                  </div>
                </template>
                <div class="feed">
                  <div v-if="!feedView.length" class="feed-empty">{{ t('situation.noLiveAlarmsHint') }}</div>
                  <div v-for="a in feedView" :key="a.id" class="feed-item" :class="{ fresh: a._new }">
                    <span class="feed-dot" :style="{ background: sevColor(a.severity) }" />
                    <div class="feed-body">
                      <div class="feed-top">
                        <SevBadge :value="a.severity" />
                        <span class="feed-rule">{{ a.ruleName }}</span>
                        <span class="feed-entity mono">{{ a.entity }}</span>
                        <span class="feed-time mono">{{ d(a.timestamp, 'time') }}</span>
                      </div>
                      <div class="feed-msg">{{ a.message }}</div>
                    </div>
                  </div>
                </div>
              </el-card>
            </el-col>
            <el-col :span="11">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.topRiskAlarms') }}</template>
                <el-table :data="sitStats?.topRisk ?? []" size="small" height="368">
                  <el-table-column :label="t('situation.score')" width="86">
                    <template #default="{ row }">
                      <span class="risk-pill" :style="{ background: sevColor(row.riskLevel) }">{{ row.riskScore }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="ruleName" :label="t('common.rule')" min-width="150" show-overflow-tooltip />
                  <el-table-column prop="entity" :label="t('common.entity')" width="130" show-overflow-tooltip />
                  <el-table-column label="ATT&CK" width="92">
                    <template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.mitre || '—' }}</span></template>
                  </el-table-column>
                  <el-table-column :label="t('situation.level')" width="94">
                    <template #default="{ row }"><SevBadge :value="row.severity" /></template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>
        </div>

</template>
