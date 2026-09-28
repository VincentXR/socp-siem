<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/skeleton/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElSkeleton, ElSkeletonItem } from 'element-plus/es/components/skeleton/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed } from 'vue'
import AnimatedNumber from '../AnimatedNumber.vue'
import EmptyState from '../components/EmptyState.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import TrendChart from '../components/TrendChart.vue'
import { HEALTH_TARGETS } from '../api'
import type { Alarm } from '../api'
import { sevColor } from '../lib/ui'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'

const props = defineProps<{
  stat: { total: number; critical: number; high: number; activeCases: number; online: number }
  sitStats?: {
    trend7d?: Record<string, number>
    bySeverity?: Record<string, number>
    topRisk?: Array<{ id: string; ruleName: string; entity: string; severity: string; riskScore?: number; mitre?: string | null }>
  } | null
  filteredAlarms: Alarm[]
  healths: Record<string, string>
  loading?: boolean
  refreshing?: boolean
  updatedAt?: number
  error?: string
  goAlarms?: (query?: Record<string, string>) => void
  openAlarm?: (id: string) => void
  goCases?: () => void
  goSearch?: () => void
  goSoar?: () => void
}>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

const { t, d } = useI18n()
const LEVELS = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const

const refreshLabel = computed(() => props.updatedAt
  ? t('overview.lastRefresh', { time: d(new Date(props.updatedAt), 'dateTime') })
  : t('overview.awaitingRefresh'))
const trendSum = computed(() => Object.values(props.sitStats?.trend7d ?? {}).reduce((a, b) => a + b, 0))
const highPending = computed(() => props.stat.critical + props.stat.high)
const maxLevel = computed(() => Math.max(1, ...LEVELS.map(level => props.sitStats?.bySeverity?.[level] ?? 0)))
const topRisk = computed(() => (props.sitStats?.topRisk ?? []).slice(0, 5))
const latestAlarms = computed(() => props.filteredAlarms.slice(0, 5))
const initialLoading = computed(() => Boolean(props.loading && !props.updatedAt))
const knownServices = computed(() => HEALTH_TARGETS.filter(target => Boolean(props.healths[target.name])))
const degradedServices = computed(() => knownServices.value.filter(target => props.healths[target.name] !== 'up').length)

function onRefresh(): void { emit('refresh') }
function openAllAlarms(): void { props.goAlarms?.() }
function openHighRiskAlarms(): void { props.goAlarms?.({ severity: 'HIGH' }) }
function openCases(): void { props.goCases?.() }
function openRecentAlarm(row: unknown): void {
  const id = (row as Alarm)?.id
  if (id) props.openAlarm?.(id)
}
function serviceState(name: string): 'up' | 'down' | 'unknown' {
  const status = props.healths[name]
  return status === 'up' ? 'up' : status ? 'down' : 'unknown'
}
function serviceStateLabel(name: string): string { return t(`overview.serviceState.${serviceState(name)}`) }
const timeOnly = (iso: string) => (iso?.length >= 19 ? iso.slice(11, 19) : '—')
function getStatusLabel(status: string): string { return tOr(t, `statuses.${status}`, status) }
function severityLabel(level: string): string { return tOr(t, `severities.${level}`, level) }
</script>

<template>
  <div class="page-pad view-enter overview-page">
    <PageHeader :eyebrow="t('menuGroup.operations')" :title="t('overview.title')">
      <template #description>{{ t('overview.shiftDescription') }} · {{ refreshLabel }}</template>
      <template #actions>
        <span class="ov-date-pill">{{ t('overview.last7Days') }}</span>
        <el-button type="primary" size="small" :loading="props.refreshing" @click="onRefresh">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>

    <div v-if="props.error" class="overview-data-warning" role="alert">
      <strong>{{ t('overview.dataUnavailable') }}</strong>
      <span>{{ props.error }}</span>
    </div>

    <el-skeleton v-if="initialLoading" class="overview-skeleton" animated aria-live="polite">
      <template #template>
        <div class="overview-skeleton-kpis">
          <el-skeleton-item v-for="index in 3" :key="index" variant="rect" />
        </div>
        <div class="overview-skeleton-panels">
          <el-skeleton-item variant="rect" />
          <el-skeleton-item variant="rect" />
        </div>
      </template>
    </el-skeleton>

    <template v-else>
      <section class="overview-section" aria-labelledby="overview-attention-title">
        <div class="overview-section-head">
          <div>
            <h2 id="overview-attention-title">{{ t('overview.needsAttention') }}</h2>
            <p>{{ t('overview.needsAttentionHint') }}</p>
          </div>
          <span class="overview-window mono">{{ t('overview.totalWindow', { count: stat.total }) }}</span>
        </div>

        <div class="overview-kpis">
          <MetricCard :label="t('overview.highCriticalAlarms7d')" tone="danger" :interactive="Boolean(props.goAlarms)" @click="openHighRiskAlarms">
            <AnimatedNumber :value="highPending" />
            <template #hint>{{ severityLabel('CRITICAL') }} <b class="mono">{{ stat.critical }}</b> · {{ severityLabel('HIGH') }} <b class="mono">{{ stat.high }}</b></template>
          </MetricCard>
          <MetricCard :label="t('overview.activeCases')" tone="warning" :interactive="Boolean(props.goCases)" @click="openCases">
            <AnimatedNumber :value="stat.activeCases" />
            <template #hint>{{ t('overview.activeCasesHint') }}</template>
          </MetricCard>
          <MetricCard :label="t('overview.degradedServices')" :tone="degradedServices ? 'danger' : 'success'">
            <AnimatedNumber :value="degradedServices" />
            <template #hint>{{ knownServices.length ? t('overview.healthCoverage', { known: knownServices.length, total: HEALTH_TARGETS.length }) : t('overview.healthAwaiting') }}</template>
          </MetricCard>
        </div>
      </section>

      <section class="overview-work-grid" :aria-label="t('overview.analystWorkQueue')">
        <el-card shadow="never" class="ov-card overview-priority-card">
          <template #header>
            <div class="ov-card-head">
              <div>
                <strong>{{ t('overview.priorityQueue') }}</strong>
                <span class="overview-card-hint">{{ t('overview.priorityQueueHint') }}</span>
              </div>
              <el-button text size="small" @click="openAllAlarms">{{ t('overview.viewAllAlarms') }}</el-button>
            </div>
          </template>
          <div v-if="topRisk.length" class="overview-priority-list">
            <button v-for="(risk, index) in topRisk" :key="risk.id" type="button" class="overview-priority-item" @click="openRecentAlarm(risk)">
              <span class="overview-priority-rank mono">{{ String(index + 1).padStart(2, '0') }}</span>
              <span class="overview-priority-copy">
                <strong>{{ risk.ruleName }}</strong>
                <span class="mono">{{ risk.entity || t('time.notAvailable') }}<template v-if="risk.mitre"> · {{ risk.mitre }}</template></span>
              </span>
              <SevBadge :value="risk.severity" />
              <span class="overview-priority-score mono">{{ risk.riskScore ?? '—' }}</span>
              <span class="overview-priority-arrow" aria-hidden="true">→</span>
            </button>
          </div>
          <EmptyState v-else :title="t('overview.noHighRiskAlarms')" :description="t('overview.noUrgentRiskItems')" />
        </el-card>

        <el-card shadow="never" class="ov-card overview-actions-card">
          <template #header>
            <div>
              <strong>{{ t('overview.shiftActions') }}</strong>
              <span class="overview-card-hint">{{ t('overview.shiftActionsHint') }}</span>
            </div>
          </template>
          <div class="overview-actions">
            <button type="button" @click="openAllAlarms"><span aria-hidden="true">01</span><strong>{{ t('overview.reviewAlarms') }}</strong><small>{{ t('overview.reviewAlarmsHint') }}</small></button>
            <button type="button" @click="openCases"><span aria-hidden="true">02</span><strong>{{ t('overview.openCases') }}</strong><small>{{ t('overview.openCasesHint') }}</small></button>
            <button type="button" @click="props.goSearch?.()"><span aria-hidden="true">03</span><strong>{{ t('overview.searchLogs') }}</strong><small>{{ t('overview.searchLogsHint') }}</small></button>
            <button v-if="props.goSoar" type="button" @click="props.goSoar"><span aria-hidden="true">04</span><strong>{{ t('overview.openSoar') }}</strong><small>{{ t('overview.openSoarHint') }}</small></button>
          </div>
        </el-card>
      </section>

      <section class="overview-section" aria-labelledby="overview-trends-title">
        <div class="overview-section-head">
          <div><h2 id="overview-trends-title">{{ t('overview.trendsAndCoverage') }}</h2><p>{{ t('overview.trendsAndCoverageHint') }}</p></div>
        </div>
        <div class="overview-analytics-grid">
          <el-card shadow="never" class="ov-card">
            <template #header><div class="ov-card-head"><span>{{ t('overview.alarmTrend') }}</span><span class="ov-card-sub">{{ t('overview.dailyTotal', { total: trendSum }) }}</span></div></template>
            <TrendChart :data="sitStats?.trend7d" style="height: 216px" />
          </el-card>
          <el-card shadow="never" class="ov-card overview-severity-card">
            <template #header><span>{{ t('overview.sevDistribution') }}</span></template>
            <div class="ov-level-bar">
              <div v-for="level in LEVELS" :key="level" class="ov-level-seg" :style="{ flex: (sitStats?.bySeverity?.[level] ?? 0) / maxLevel + 0.02, background: sevColor(level) }" :title="`${severityLabel(level)}: ${sitStats?.bySeverity?.[level] ?? 0}`" />
            </div>
            <div class="ov-level-legend">
              <span v-for="level in LEVELS" :key="level" class="ov-level-item"><i class="ov-level-dot" :style="{ background: sevColor(level) }" />{{ severityLabel(level) }}<b class="mono">{{ sitStats?.bySeverity?.[level] ?? 0 }}</b></span>
            </div>
          </el-card>
        </div>
      </section>

      <section class="overview-lower-grid">
        <el-card shadow="never" class="ov-card">
          <template #header><div class="ov-card-head"><span>{{ t('overview.recentAlarms') }}</span><el-button text size="small" @click="openAllAlarms">{{ t('common.more') }}</el-button></div></template>
          <div v-if="latestAlarms.length" class="ov-alert-table">
            <el-table :data="latestAlarms" size="small" @row-click="openRecentAlarm">
              <el-table-column :label="t('common.timestamp')" width="96"><template #default="{ row }"><span class="mono">{{ timeOnly(row.occurredAt) }}</span></template></el-table-column>
              <el-table-column :label="t('common.severity')" width="112"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column :label="t('overview.ruleEntity')" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="table-text"><span class="ov-alert-rule">{{ row.ruleName }}</span><span class="ov-alert-entity mono"> · {{ row.entity }}</span></span></template></el-table-column>
              <el-table-column :label="t('common.status')" width="96"><template #default="{ row }"><span class="ov-alert-status" :data-s="row.status">{{ getStatusLabel(row.status) }}</span></template></el-table-column>
            </el-table>
          </div>
          <EmptyState v-else :title="t('overview.noLiveAlarms')" :description="t('overview.alarmsWillAppear')" />
        </el-card>

        <el-card shadow="never" class="ov-card overview-health-card">
          <template #header><div class="ov-card-head"><span>{{ t('overview.platformServiceHealth') }}</span><span class="ov-card-sub">{{ stat.online }} / {{ knownServices.length || '—' }} {{ t('overview.healthy') }}</span></div></template>
          <div class="overview-health-list">
            <div v-for="health in HEALTH_TARGETS" :key="health.name" class="overview-health-item">
              <span class="ov-chip-dot" :class="serviceState(health.name)" />
              <span>{{ health.name }}</span>
              <small :class="serviceState(health.name)">{{ serviceStateLabel(health.name) }}</small>
            </div>
          </div>
        </el-card>
      </section>
    </template>
  </div>
</template>

<style scoped>
.overview-page { --overview-gap: 16px; }
.overview-data-warning { display: flex; gap: 10px; margin-bottom: 16px; padding: 10px 12px; border: 1px solid color-mix(in srgb, var(--ns-warning) 32%, var(--ns-border)); border-radius: 8px; background: color-mix(in srgb, var(--ns-warning) 7%, var(--ns-surface)); color: var(--ns-text-2); font-size: 12px; }
.overview-data-warning strong { color: var(--ns-warning); }
.overview-skeleton-kpis, .overview-kpis { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: var(--overview-gap); }
.overview-skeleton-kpis .el-skeleton__item { height: 126px; border-radius: 10px; }
.overview-skeleton-panels { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: var(--overview-gap); margin-top: var(--overview-gap); }
.overview-skeleton-panels .el-skeleton__item { height: 320px; border-radius: 10px; }
.overview-section { margin-bottom: 24px; }
.overview-section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.overview-section-head h2 { margin: 0; color: var(--ns-text); font-size: 14px; font-weight: 680; letter-spacing: -.01em; }
.overview-section-head p { margin: 4px 0 0; color: var(--ns-text-3); font-size: 12px; }
.overview-window { color: var(--ns-text-3); }
.overview-work-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(290px, .85fr); gap: var(--overview-gap); margin-bottom: 26px; }
.ov-card-head { width: 100%; }
.ov-card-head > div, .overview-actions-card :deep(.el-card__header) > div { display: flex; flex-direction: column; gap: 3px; }
.overview-card-hint { color: var(--ns-text-3); font-size: 11px; font-weight: 400; }
.overview-priority-list { display: flex; flex-direction: column; }
.overview-priority-item { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto 42px 20px; align-items: center; gap: 10px; width: 100%; min-height: 58px; padding: 8px 6px; border: 0; border-bottom: 1px solid var(--ns-border); background: transparent; color: var(--ns-text); cursor: pointer; font: inherit; text-align: left; }
.overview-priority-item:last-child { border-bottom: 0; }
.overview-priority-item:hover { background: var(--ns-surface-muted); }
.overview-priority-item:focus-visible { outline: none; box-shadow: inset var(--ns-focus); }
.overview-priority-rank { color: var(--ns-text-3); }
.overview-priority-copy { min-width: 0; }
.overview-priority-copy strong, .overview-priority-copy span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.overview-priority-copy strong { font-size: 13px; font-weight: 620; }
.overview-priority-copy span { margin-top: 3px; color: var(--ns-text-3); font-size: 11px; }
.overview-priority-score { color: var(--ns-text-2); font-weight: 650; text-align: right; }
.overview-priority-arrow { color: var(--ns-text-3); }
.overview-actions { display: grid; gap: 7px; }
.overview-actions button { display: grid; grid-template-columns: 28px 1fr; gap: 2px 10px; width: 100%; padding: 10px; border: 1px solid var(--ns-border); border-radius: 8px; background: var(--ns-surface); color: var(--ns-text); cursor: pointer; font: inherit; text-align: left; }
.overview-actions button:hover { border-color: color-mix(in srgb, var(--ns-accent) 32%, var(--ns-border)); background: var(--ns-accent-subtle); }
.overview-actions button > span { grid-row: 1 / 3; align-self: center; color: var(--ns-accent-fg); font-family: var(--ns-font-mono); font-size: 11px; }
.overview-actions strong { font-size: 12px; font-weight: 650; }
.overview-actions small { color: var(--ns-text-3); font-size: 11px; }
.overview-analytics-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(280px, .7fr); gap: var(--overview-gap); }
.overview-severity-card :deep(.el-card__body) { padding-top: 22px; }
.overview-lower-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(290px, .7fr); gap: var(--overview-gap); }
.overview-health-list { display: grid; gap: 2px; }
.overview-health-item { display: grid; grid-template-columns: 10px minmax(0, 1fr) auto; align-items: center; gap: 9px; min-height: 34px; padding: 0 5px; border-bottom: 1px solid var(--ns-border); color: var(--ns-text-2); font-size: 12px; }
.overview-health-item:last-child { border-bottom: 0; }
.overview-health-item small { color: var(--ns-text-3); font-size: 10px; text-transform: uppercase; }
.overview-health-item small.up { color: var(--ns-success); }
.overview-health-item small.down { color: var(--ns-danger); }
.ov-chip-dot.unknown { background: var(--ns-text-3); }
@media (max-width: 1080px) { .overview-work-grid, .overview-lower-grid { grid-template-columns: 1fr; } }
@media (max-width: 760px) {
  .overview-skeleton-kpis, .overview-kpis, .overview-analytics-grid { grid-template-columns: 1fr; }
  .overview-skeleton-panels { grid-template-columns: 1fr; }
  .overview-section-head { align-items: flex-start; flex-direction: column; }
  .overview-priority-item { grid-template-columns: 24px minmax(0, 1fr) auto; }
  .overview-priority-score, .overview-priority-arrow { display: none; }
}
</style>
