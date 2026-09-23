<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/progress/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElProgress from 'element-plus/es/components/progress/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'
import TrendChart from '../components/TrendChart.vue'
import SevBadge from '../components/SevBadge.vue'
import PageHeader from '../components/PageHeader.vue'
import {
  alarmStats, ApiError, currentSession, gasEngineStats, gasRecentAlerts, ingestSummary, SEVERITIES,
  type ApiRequestOptions,
  type AlarmStats, type GasAlert, type GasStats, type IngestSummary,
} from '../api'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'session-expired': []; 'go-alarm': [id: string] }>()

const { t, d, locale } = useI18n()

const liveFeed = ref<Array<GasAlert & { _new?: boolean }>>([])
const liveOn = ref(true)
const liveSevFilter = ref('')
type AlertStreamState = 'off' | 'connecting' | 'connected' | 'reconnecting'
const alertStreamState = ref<AlertStreamState>('connecting')
const epsHistory = ref<number[]>([])
let alertStream: EventSource | null = null
let reconnectTimer: number | null = null
let reconnectAttempt = 0
let streamGeneration = 0
const gaugeEl = ref<HTMLElement>()
const donutEl = ref<HTMLElement>()
const epsEl = ref<HTMLElement>()
const chartGauge = shallowRef<ECharts>()
const chartDonut = shallowRef<ECharts>()
const chartEps = shallowRef<ECharts>()
let renderToken = 0

interface SituationSnapshot {
  stats: AlarmStats | null
  engine: GasStats | null
  recent: GasAlert[]
  ingest: IngestSummary | null
  recentAvailable: boolean
  ingestFresh: boolean
  stale: boolean
  errors: string[]
}
const queryClient = useQueryClient()
const situationKey = ['situation', 'snapshot'] as const

const situationQuery = useQuery({
  queryKey: situationKey,
  queryFn: async ({ signal }) => {
    const options: ApiRequestOptions = { signal }
    const [stats, engine, recent, ingest] = await Promise.allSettled([
      alarmStats(options), gasEngineStats(options), gasRecentAlerts(options), ingestSummary(options),
    ])
    if (signal.aborted) throw signal.reason ?? new DOMException('Aborted', 'AbortError')
    const previous = queryClient.getQueryData<SituationSnapshot>(situationKey)
    const errors = [stats, engine, recent, ingest]
      .filter(result => result.status === 'rejected')
      .map(result => result.status === 'rejected'
        ? (result.reason instanceof Error ? result.reason.message : String(result.reason))
        : '')
      .filter(Boolean)
    const snapshot: SituationSnapshot = {
      stats: stats.status === 'fulfilled' ? stats.value : previous?.stats ?? null,
      engine: engine.status === 'fulfilled' ? engine.value : previous?.engine ?? null,
      recent: recent.status === 'fulfilled' ? recent.value : previous?.recent ?? [],
      ingest: ingest.status === 'fulfilled' ? ingest.value : previous?.ingest ?? null,
      recentAvailable: recent.status === 'fulfilled' || (previous?.recentAvailable ?? false),
      ingestFresh: ingest.status === 'fulfilled',
      stale: (stats.status === 'rejected' && !!previous?.stats)
        || (engine.status === 'rejected' && !!previous?.engine)
        || (recent.status === 'rejected' && !!previous?.recentAvailable)
        || (ingest.status === 'rejected' && !!previous?.ingest),
      errors,
    }
    return snapshot
  },
  refetchInterval: 15_000,
  refetchIntervalInBackground: false,
})
const sitStats = computed<AlarmStats | null>(() => situationQuery.data.value?.stats ?? null)
const sitEngine = computed<GasStats | null>(() => situationQuery.data.value?.engine ?? null)
const sitIngest = computed<IngestSummary | null>(() => situationQuery.data.value?.ingest ?? null)
const situationErrors = computed(() => situationQuery.data.value?.errors ?? [])
const situationStale = computed(() => situationQuery.data.value?.stale ?? false)
const recentAvailable = computed(() => situationQuery.data.value?.recentAvailable ?? false)
const situationFetching = computed(() => situationQuery.isFetching.value)

function cssToken(variable: string, fallback: string) {
  if (typeof document === 'undefined') return fallback
  const value = getComputedStyle(document.documentElement).getPropertyValue(variable).trim()
  return value || fallback
}
function sevColor(severity: string) {
  if (severity === 'CRITICAL' || severity === 'HIGH') return cssToken('--ns-danger', '#dc2626')
  if (severity === 'MEDIUM') return cssToken('--ns-warning', '#a16207')
  return cssToken('--ns-info', '#667085')
}
function tc(light: string, dark: string) { return props.theme === 'dark' ? dark : light }
function severityLabel(severity: string): string {
  return tOr(t, `severities.${severity}`, severity)
}
const feedView = computed(() => liveSevFilter.value ? liveFeed.value.filter(alert => alert.severity === liveSevFilter.value) : liveFeed.value)
const queuePct = computed(() => Math.round((sitEngine.value?.queueLoad ?? 0) * 1000) / 10)
const queueColor = computed(() => queuePct.value > 70
  ? cssToken('--ns-danger', '#dc2626')
  : queuePct.value > 30 ? cssToken('--ns-warning', '#a16207') : cssToken('--ns-success', '#15803d'))

function openAlarm(id: unknown): void {
  const value = String(id ?? '').trim()
  if (value) emit('go-alarm', value)
}

function openRiskRow(row: { id?: string }): void {
  openAlarm(row.id)
}

function clearReconnectTimer(): void {
  if (reconnectTimer !== null) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
}

function scheduleAlertReconnect(): void {
  if (!liveOn.value || document.visibilityState === 'hidden') {
    alertStreamState.value = 'off'
    return
  }
  clearReconnectTimer()
  const delay = Math.min(30_000, 1_000 * 2 ** Math.min(reconnectAttempt++, 5))
  alertStreamState.value = 'reconnecting'
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    openAlertStream()
  }, delay)
}

function openAlertStream(): void {
  if (!liveOn.value || document.visibilityState === 'hidden' || alertStream) return
  clearReconnectTimer()
  const generation = ++streamGeneration
  alertStreamState.value = 'connecting'
  try {
    const source = new EventSource('/detect-web/api/v1/stream')
    alertStream = source
    source.onopen = () => {
      if (generation !== streamGeneration) return
      reconnectAttempt = 0
      alertStreamState.value = 'connected'
    }
    source.addEventListener('alert', (event: MessageEvent) => {
      if (generation !== streamGeneration) return
      try {
        const value = JSON.parse(event.data)
        if (value && value.ruleId) {
          mergeFeed([{
            id: value.id ?? `sse-${value.ruleId}-${value.timestamp}`,
            timestamp: value.timestamp ?? new Date().toISOString(),
            ruleId: value.ruleId, ruleName: value.ruleName ?? '', title: value.title ?? value.ruleName ?? '', severity: value.severity ?? 'INFO',
            message: value.message ?? '', entity: value.entity ?? '',
          }])
          void loadSituation()
        }
      } catch { /* 忽略异常帧 */ }
    })
    source.onerror = () => {
      if (generation !== streamGeneration) return
      source.close()
      alertStream = null
      void currentSession().catch(error => {
        if (generation === streamGeneration && error instanceof ApiError && error.status === 401) emit('session-expired')
      })
      scheduleAlertReconnect()
    }
  } catch {
    if (generation === streamGeneration) scheduleAlertReconnect()
  }
}
function closeAlertStream(): void {
  streamGeneration += 1
  clearReconnectTimer()
  if (alertStream) { alertStream.close(); alertStream = null }
  alertStreamState.value = 'off'
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
          axisLine: { lineStyle: { width: 14, color: [[0.2, cssToken('--ns-success', '#15803d')], [0.4, cssToken('--ns-success', '#15803d')], [0.65, cssToken('--ns-warning', '#a16207')], [0.85, cssToken('--ns-danger', '#dc2626')], [1, cssToken('--ns-danger', '#dc2626')]] } },
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
      chartEps.value.setOption({ grid: { left: 34, right: 10, top: 18, bottom: 20 }, tooltip: { trigger: 'axis' }, xAxis: { type: 'category', show: false, data: epsHistory.value.map((_, index) => index) }, yAxis: { type: 'value', axisLabel: { fontSize: 10 }, splitLine: { lineStyle: { type: 'dashed' } } }, series: [{ type: 'line', smooth: true, showSymbol: false, data: epsHistory.value, lineStyle: { color: cssToken('--ns-success', '#15803d'), width: 2 }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: tc('rgba(21,128,61,.28)', 'rgba(63,185,80,.35)') }, { offset: 1, color: 'rgba(0,0,0,0)' }]) } }] })
    }
  }, 80)
}
function toggleLive() {
  liveOn.value = !liveOn.value
  if (liveOn.value) {
    reconnectAttempt = 0
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
  if (snapshot.ingestFresh && snapshot.ingest) epsHistory.value = [...epsHistory.value, snapshot.ingest.eps1m ?? 0].slice(-40)
  renderSitCharts()
})
onMounted(() => {
  if (liveOn.value && document.visibilityState === 'visible') openAlertStream()
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
  <div class="page-pad view-enter sit-wrap">
    <PageHeader :eyebrow="t('menuGroup.overview')" :title="t('situation.title')" :description="t('situation.description')">
      <template #actions>
        <el-button size="small" :loading="situationFetching" @click="loadSituation">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>
    <el-alert
      v-if="situationErrors.length"
      :title="situationStale ? t('situation.staleData') : situationErrors.length === 4 ? t('situation.dataUnavailable') : t('situation.partialData')"
      :description="situationErrors.join(' · ')"
      type="warning"
      :closable="false"
      show-icon
      class="situation-data-warning"
    />

    <div class="sit-kpis">
            <div class="sit-kpi">
              <div class="k-num">{{ sitEngine?.eventCount ?? t('time.notAvailable') }}</div><div class="k-label">{{ t('situation.engineEvents') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:var(--ns-danger)">{{ sitEngine?.alertCount ?? t('time.notAvailable') }}</div><div class="k-label">{{ t('situation.ruleAlerts') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:var(--ns-warning)">{{ sitEngine?.suppressedCount ?? t('time.notAvailable') }}</div><div class="k-label">{{ t('situation.suppressedDedup') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" :style="{ color: (sitEngine?.dropCount ?? 0) > 0 ? 'var(--ns-danger)' : 'var(--ns-success)' }">{{ sitEngine?.dropCount ?? t('time.notAvailable') }}</div>
              <div class="k-label">{{ t('situation.backpressureDrops') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num" style="color:var(--ns-accent-fg)">{{ sitIngest?.eps1m ?? t('time.notAvailable') }}</div><div class="k-label">{{ t('situation.ingestEps') }}</div>
            </div>
            <div class="sit-kpi">
              <div class="k-num">{{ sitEngine ? `${queuePct}%` : t('time.notAvailable') }}</div>
              <div class="k-label">{{ t('situation.queueLevel') }}</div>
              <el-progress v-if="sitEngine" :percentage="Math.min(100, queuePct)" :show-text="false" :stroke-width="4"
                :color="queueColor" style="margin-top:4px" />
            </div>
          </div>

          <el-row class="metrics-row" :gutter="12" style="margin-bottom:12px">
            <el-col :xs="24" :md="12">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.threatScore') }}（0–100）</template>
                <div v-if="sitStats" ref="gaugeEl" style="height:180px"></div>
                <div v-else class="feed-empty" style="height:180px">{{ t('situation.dataUnavailable') }}</div>
                <div style="text-align:center;font-size:12px;color:var(--ns-text-3)">
                  {{ t('situation.sevenDayAlarms') }} <b style="color:var(--ns-text)">{{ sitStats?.total ?? t('time.notAvailable') }}</b>
                  · {{ t('situation.highRisk') }} <b style="color:var(--ns-danger)">{{ sitStats ? (sitStats.byRiskLevel?.CRITICAL ?? 0) + (sitStats.byRiskLevel?.HIGH ?? 0) : t('time.notAvailable') }}</b>
                </div>
              </el-card>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.sevenDayRiskDistribution') }}</template>
                <div v-if="sitStats" ref="donutEl" style="height:210px"></div>
                <div v-else class="feed-empty" style="height:210px">{{ t('situation.dataUnavailable') }}</div>
              </el-card>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.sevenDayTrend') }}</template>
                <TrendChart v-if="sitStats" :data="sitStats.trend7d" variant="situation" style="height:210px" />
                <div v-else class="feed-empty" style="height:210px">{{ t('situation.dataUnavailable') }}</div>
              </el-card>
            </el-col>
            <el-col :xs="24" :md="12">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.ingestThroughput') }}（EPS）</template>
                <div v-if="sitIngest" ref="epsEl" style="height:210px"></div>
                <div v-else class="feed-empty" style="height:210px">{{ t('situation.dataUnavailable') }}</div>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="12">
            <el-col :xs="24" :lg="13">
              <el-card shadow="never" class="sit-card">
                <template #header>
                  <div style="display:flex;align-items:center;gap:10px">
                    <span class="live-dot" :class="{ off: !liveOn || alertStreamState !== 'connected', reconnecting: alertStreamState === 'reconnecting' }" />
                    <span>{{ t('situation.liveEventStream') }}</span>
                    <span class="live-status">{{ t(`situation.stream${alertStreamState.charAt(0).toUpperCase()}${alertStreamState.slice(1)}`) }}</span>
                    <el-select v-model="liveSevFilter" :placeholder="t('situation.allLevels')" clearable size="small" style="width:120px">
                      <el-option v-for="s in SEVERITIES" :key="s" :label="severityLabel(s)" :value="s" />
                    </el-select>
                    <el-button size="small" @click="toggleLive">{{ liveOn ? t('situation.pause') : t('situation.resume') }}</el-button>
                    <span style="margin-left:auto;font-size:12px;color:var(--ns-text-3)">{{ t('situation.eventCount', { count: feedView.length }) }}</span>
                  </div>
                </template>
                <div class="feed">
                  <div v-if="!feedView.length" class="feed-empty">{{ recentAvailable ? t('situation.noLiveAlarmsHint') : t('situation.dataUnavailable') }}</div>
                  <div v-for="a in feedView" :key="a.id" class="feed-item situation-clickable" :class="{ fresh: a._new }" role="button" tabindex="0" @click="openAlarm(a.id)" @keydown.enter.space.prevent="openAlarm(a.id)">
                    <span class="feed-dot" :style="{ background: sevColor(a.severity) }" />
                    <div class="feed-body">
                      <div class="feed-top">
                        <SevBadge :value="a.severity" />
                        <span class="feed-rule">{{ a.title || a.ruleName }}</span>
                        <span class="feed-entity mono">{{ a.entity }}</span>
                        <span class="feed-time mono">{{ d(a.timestamp, 'time') }}</span>
                      </div>
                      <div class="feed-msg">{{ a.message }}</div>
                    </div>
                  </div>
                </div>
              </el-card>
            </el-col>
            <el-col :xs="24" :lg="11">
              <el-card shadow="never" class="sit-card">
                <template #header>{{ t('situation.topRiskAlarms') }}</template>
                <el-table :data="sitStats?.topRisk ?? []" size="small" height="368" :empty-text="sitStats ? t('common.empty') : t('situation.dataUnavailable')" @row-click="openRiskRow">
                  <el-table-column :label="t('situation.score')" width="86">
                    <template #default="{ row }">
                      <span class="risk-pill" :class="`risk-${String(row.riskLevel || 'INFO').toLowerCase()}`">{{ row.riskScore }}</span>
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
