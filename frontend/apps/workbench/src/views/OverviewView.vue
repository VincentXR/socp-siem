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
import RowActivate from '../components/RowActivate.vue'
import TrendChart from '../components/TrendChart.vue'
import { HEALTH_TARGETS } from '../api'
import type { Alarm } from '../api'
import { useI18n } from '../composables/useI18n'
import { tOr } from '../utils/i18nLabel'
import { ALARM_STATUS_ALL } from '../app/alarm-statuses'
import { MENU_ICONS } from '../app/navigation'

const props = defineProps<{
  availability?: { alarms: boolean; priority?: boolean; stats: boolean; cases: boolean }
  healthState?: string
  stat: { total: number; critical: number; high: number; activeCases: number; online: number }
  sitStats?: {
    trend7d?: Record<string, number>
    bySeverity?: Record<string, number>
  } | null
  filteredAlarms: Alarm[]
  priorityAlarms?: Alarm[]
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
  : t(props.error ? 'overview.partialRefresh' : 'overview.awaitingRefresh'))
const trendSum = computed(() => Object.values(props.sitStats?.trend7d ?? {}).reduce((a, b) => a + b, 0))
const hasTrend = computed(() => Object.keys(props.sitStats?.trend7d ?? {}).length > 0)
const classifiedTotal = computed(() => LEVELS.reduce((sum, level) => sum + (props.sitStats?.bySeverity?.[level] ?? 0), 0))
const severityRows = computed(() => {
  const rows = LEVELS.map(level => ({ level: String(level), count: props.sitStats?.bySeverity?.[level] ?? 0 }))
  const unclassified = Math.max(0, props.stat.total - classifiedTotal.value)
  if (unclassified) rows.push({ level: 'UNKNOWN', count: unclassified })
  return rows
})
function severityShare(count: number): number {
  const total = Math.max(props.stat.total, classifiedTotal.value)
  return total > 0 ? count / total * 100 : 0
}
const topRisk = computed(() => (props.priorityAlarms ?? []).slice(0, 5))
const latestAlarms = computed(() => props.filteredAlarms.slice(0, 5))
const initialLoading = computed(() => Boolean(props.loading && !props.updatedAt))
const knownServices = computed(() => HEALTH_TARGETS.filter(target => ['up', 'down'].includes(props.healths[target.name] ?? '')))
const onlineServices = computed(() => HEALTH_TARGETS.filter(target => props.healths[target.name] === 'up').length)

function onRefresh(): void { emit('refresh') }
function openAllAlarms(): void { props.goAlarms?.() }
function openSeverityAlarms(severity: string): void {
  const now = new Date()
  const start = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - 6)
  props.goAlarms?.({ status: ALARM_STATUS_ALL, severity, from: new Date(start).toISOString(), to: now.toISOString() })
}
function openHealth(): void { document.getElementById('overview-service-health')?.scrollIntoView({ behavior: 'auto', block: 'start' }) }
function openCases(): void { props.goCases?.() }
function openRecentAlarm(row: unknown): void {
  const id = (row as Alarm)?.id
  if (id) props.openAlarm?.(id)
}
function serviceState(name: string): 'up' | 'down' | 'unknown' {
  const status = props.healths[name]
  return status === 'up' ? 'up' : status === 'down' ? 'down' : 'unknown'
}
function serviceStateLabel(name: string): string { return t(`overview.serviceState.${serviceState(name)}`) }
const formatTime = (iso: string) => iso && Number.isFinite(Date.parse(iso)) ? d(iso, 'dateTime') : '—'
function getStatusLabel(status: string): string { return tOr(t, `statuses.${status}`, status) }
function severityLabel(level: string): string { return level === 'UNKNOWN' ? t('overview.unclassified') : tOr(t, `severities.${level}`, level) }
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
      <section class="overview-hero" :aria-label="t('overview.signalOverview')">
        <div class="overview-hero-grid" aria-hidden="true" />
        <div class="overview-hero-copy">
          <div class="overview-hero-kicker"><span class="overview-signal-mark" aria-hidden="true"><i /><i /><i /></span>{{ t('overview.signalOverview') }}</div>
          <div class="overview-hero-total overview-window"><AnimatedNumber v-if="availability?.stats" :value="stat.total" /><span v-else>—</span><span class="overview-hero-unit">{{ t('common.alertCount') }}</span></div>
          <p class="overview-hero-caption">{{ t('overview.totalAlarms7d') }}</p>
          <div class="overview-hero-source"><span>{{ t('overview.utcDaily') }}</span><span>{{ t('overview.sourceAlarmStats') }}</span></div>
        </div>
        <div class="overview-hero-trend">
          <div class="overview-hero-chart-head"><h2>{{ t('overview.alarmTrend') }}</h2><span class="mono">{{ t('overview.dailyTotal', { total: availability?.stats && hasTrend ? trendSum : '—' }) }}</span></div>
          <TrendChart v-if="availability?.stats && hasTrend" :data="sitStats?.trend7d" variant="console" class="overview-hero-chart" />
          <p v-else-if="availability?.stats" class="overview-hero-empty" role="status">{{ t('overview.noTrendData') }}</p>
          <p v-else class="overview-hero-empty" role="status">{{ t('experience.unknownData') }}</p>
        </div>
      </section>

      <section class="overview-section" aria-labelledby="overview-attention-title">
        <div class="overview-section-head">
          <div>
            <h2 id="overview-attention-title">{{ t('overview.needsAttention') }}</h2>
            <p>{{ t('overview.needsAttentionHint') }}</p>
          </div>
          <span class="overview-section-index mono" aria-hidden="true">01 / TRIAGE</span>
        </div>

        <div class="overview-kpis">
          <MetricCard v-for="severity in ['CRITICAL', 'HIGH']" :key="severity" :label="t(severity === 'CRITICAL' ? 'experience.critical7d' : 'experience.high7d')" :tone="severity === 'CRITICAL' ? 'danger' : 'warning'" :interactive="Boolean(props.goAlarms)" @click="openSeverityAlarms(severity)">
            <span class="overview-metric-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7l-9-4Z"/><path d="M12 8v5m0 3v.1"/></svg></span>
            <AnimatedNumber v-if="availability?.stats" :value="severity === 'CRITICAL' ? stat.critical : stat.high" /><span v-else>—</span>
            <template #hint>{{ t('overview.inspectSeverity') }}<span aria-hidden="true"> ↗</span></template>
          </MetricCard>
          <MetricCard :label="t('overview.activeCases')" tone="info" :interactive="Boolean(props.goCases)" @click="openCases">
            <span class="overview-metric-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 5h6l2 3h8v12H4V5Z"/><path d="M8 12h8m-8 4h5"/></svg></span>
            <AnimatedNumber v-if="availability?.cases" :value="stat.activeCases" /><span v-else>—</span>
            <template #hint>{{ t('overview.activeCasesHint') }}</template>
          </MetricCard>
          <MetricCard :label="t('experience.serviceHealth')" :tone="healthState === 'healthy' ? 'success' : healthState === 'degraded' ? 'danger' : 'neutral'" interactive @click="openHealth">
            <span class="overview-health-status">{{ t(`experience.state.${healthState || 'unknown'}`) }}</span>
            <span class="overview-health-segments" aria-hidden="true"><i v-for="service in HEALTH_TARGETS" :key="service.name" :data-state="serviceState(service.name)" /></span>
            <template #hint>{{ knownServices.length ? t('overview.healthCoverage', { known: knownServices.length, total: HEALTH_TARGETS.length }) : t('overview.healthAwaiting') }}</template>
          </MetricCard>
        </div>
      </section>

      <section class="overview-work-grid" :aria-label="t('overview.analystWorkQueue')">
        <el-card shadow="never" class="ov-card overview-priority-card">
          <template #header>
            <div class="ov-card-head">
              <div>
                <strong class="overview-panel-title"><span class="overview-panel-mark" aria-hidden="true" />{{ t('overview.priorityQueue') }}</strong>
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
              <span class="overview-priority-score mono" :class="{ 'is-high': risk.riskScore != null && risk.riskScore >= 80 }"><small>{{ t('overview.riskScore') }}</small>{{ risk.riskScore ?? '—' }}</span>
              <span class="overview-priority-arrow" aria-hidden="true">→</span>
            </button>
          </div>
          <EmptyState v-else-if="availability?.priority" :title="t('overview.noPendingAlarms')" :description="t('overview.noPendingRiskItems')" /><p v-else role="status">{{ t('experience.unknownData') }}</p>
        </el-card>

        <el-card shadow="never" class="ov-card overview-severity-card">
            <template #header><div class="ov-card-head"><strong>{{ t('overview.sevDistribution') }}</strong><span class="ov-card-sub">{{ t('overview.last7Days') }}</span></div></template>
            <p v-if="!availability?.stats" role="status">{{ t('experience.unknownData') }}</p>
            <div v-else class="overview-severity-list">
              <div v-for="row in severityRows" :key="row.level" class="overview-severity-row" :data-level="row.level" :style="{ '--level-color': `var(--ns-sev-${row.level.toLowerCase()}, var(--ns-text-3))` }">
                <div class="overview-severity-label"><span><i aria-hidden="true" />{{ severityLabel(row.level) }}</span><strong class="mono">{{ row.count }}</strong></div>
                <div class="overview-severity-track" aria-hidden="true"><span class="overview-severity-fill" :style="{ width: `${severityShare(row.count)}%` }" /></div>
              </div>
            </div>
        </el-card>
      </section>

      <section class="overview-actions" :aria-label="t('overview.shiftActions')">
        <button type="button" @click="openAllAlarms"><span class="overview-action-icon" aria-hidden="true"><svg viewBox="0 0 24 24" v-html="MENU_ICONS.alarm" /></span><strong>{{ t('overview.reviewAlarms') }}</strong><small>{{ t('overview.reviewAlarmsHint') }}</small><span class="overview-action-arrow" aria-hidden="true">↗</span></button>
        <button type="button" @click="openCases"><span class="overview-action-icon" aria-hidden="true"><svg viewBox="0 0 24 24" v-html="MENU_ICONS.case" /></span><strong>{{ t('overview.openCases') }}</strong><small>{{ t('overview.openCasesHint') }}</small><span class="overview-action-arrow" aria-hidden="true">↗</span></button>
        <button type="button" @click="props.goSearch?.()"><span class="overview-action-icon" aria-hidden="true"><svg viewBox="0 0 24 24" v-html="MENU_ICONS.search" /></span><strong>{{ t('overview.searchLogs') }}</strong><small>{{ t('overview.searchLogsHint') }}</small><span class="overview-action-arrow" aria-hidden="true">↗</span></button>
        <button v-if="props.goSoar" type="button" @click="props.goSoar"><span class="overview-action-icon" aria-hidden="true"><svg viewBox="0 0 24 24" v-html="MENU_ICONS.soar" /></span><strong>{{ t('overview.openSoar') }}</strong><small>{{ t('overview.openSoarHint') }}</small><span class="overview-action-arrow" aria-hidden="true">↗</span></button>
      </section>

      <section class="overview-lower-grid">
        <el-card shadow="never" class="ov-card">
          <template #header><div class="ov-card-head"><span>{{ t('overview.recentAlarms') }}</span><el-button text size="small" @click="openAllAlarms">{{ t('overview.viewAllAlarms') }}</el-button></div></template>
          <div v-if="latestAlarms.length" class="ov-alert-table">
            <el-table :data="latestAlarms" size="small" @row-click="openRecentAlarm">
              <el-table-column :label="t('common.timestamp')" width="170"><template #default="{ row }"><span class="mono">{{ formatTime(row.occurredAt) }}</span></template></el-table-column>
              <el-table-column :label="t('common.severity')" width="112"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
              <el-table-column :label="t('overview.ruleEntity')" min-width="180" show-overflow-tooltip><template #default="{ row }"><RowActivate :aria-label="row.ruleName" @activate="openRecentAlarm(row)"><span class="table-text"><span class="ov-alert-rule">{{ row.ruleName }}</span><span class="ov-alert-entity mono"> · {{ row.entity }}</span></span></RowActivate></template></el-table-column>
              <el-table-column :label="t('common.status')" width="96"><template #default="{ row }"><span class="ov-alert-status" :data-s="row.status">{{ getStatusLabel(row.status) }}</span></template></el-table-column>
            </el-table>
          </div>
          <EmptyState v-else-if="availability?.alarms" :title="t('overview.noLiveAlarms')" :description="t('overview.alarmsWillAppear')" /><p v-else role="status">{{ t('experience.unknownData') }}</p>
        </el-card>

        <el-card shadow="never" id="overview-service-health" class="ov-card overview-health-card">
          <template #header><div class="ov-card-head"><span>{{ t('overview.platformServiceHealth') }}</span><span class="ov-card-sub">{{ onlineServices }} / {{ knownServices.length || '—' }} {{ t('overview.healthy') }}</span></div></template>
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
.overview-page { --overview-gap: 18px; background: radial-gradient(ellipse at 100% 0, color-mix(in srgb, var(--ns-accent) 5%, transparent), transparent 45%); }
.overview-page :deep(.page-header) { margin-bottom: 20px; }
.overview-hero { position: relative; isolation: isolate; display: grid; grid-template-columns: minmax(240px, .85fr) minmax(0, 1.6fr); gap: 32px; margin-bottom: 26px; padding: 28px 32px 24px; overflow: hidden; border: 1px solid #2c4568; border-radius: 16px; color: #eff6ff; background: radial-gradient(ellipse at 75% 0, #163f66 0, transparent 60%), linear-gradient(115deg, #101d36, #0b1829); box-shadow: 0 10px 30px -20px #0b294f80; }
.overview-hero::after { content: ''; position: absolute; top: 0; left: 32px; width: 96px; height: 2px; background: linear-gradient(90deg, #84acff, #66d9e8); }
.overview-hero-grid { position: absolute; inset: 0; z-index: -1; pointer-events: none; opacity: .38; background-image: linear-gradient(#6ea7db13 1px, transparent 1px), linear-gradient(90deg, #6ea7db13 1px, transparent 1px); background-size: 36px 36px; mask-image: linear-gradient(90deg, transparent, black); }
.overview-hero-copy { display: flex; flex-direction: column; justify-content: center; min-width: 0; }
.overview-hero-kicker { display: flex; align-items: center; gap: 10px; color: #a5c5ed; font-size: 12px; font-weight: 600; letter-spacing: .08em; }
.overview-signal-mark { display: flex; align-items: center; gap: 3px; height: 18px; }
.overview-signal-mark i { width: 3px; height: 7px; background: #86c4f5; border-radius: 2px; }
.overview-signal-mark i:nth-child(2) { height: 16px; background: #7ad9df; }
.overview-signal-mark i:nth-child(3) { height: 11px; }
.overview-hero-total { display: flex; align-items: baseline; flex-wrap: wrap; gap: 12px; margin-top: 14px; font-size: clamp(36px, 4.5vw, 58px); font-weight: 650; line-height: 1.1; letter-spacing: -.04em; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.overview-hero-total > :first-child { min-width: 0; max-width: 100%; }
.overview-hero-unit { color: #aac0dd; font-size: 13px; font-weight: 400; letter-spacing: 0; }
.overview-hero-caption { margin: 8px 0 22px; color: #d8e5f6; font-size: 14px; }
.overview-hero-source { display: flex; flex-wrap: wrap; gap: 7px 14px; color: #98b2d3; font-size: 12px; }
.overview-hero-source > :first-child { color: #a9dce9; }
.overview-hero-trend { min-width: 0; padding-left: 24px; border-left: 1px solid #739bc329; }
.overview-hero-chart-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.overview-hero-chart-head h2 { margin: 0; color: #d8e5f6; font-size: 13px; font-weight: 500; }
.overview-hero-chart-head > span { color: #98b2d3; font-size: 12px; }
.overview-hero-chart { height: 165px !important; }
.overview-hero-empty { display: flex; align-items: center; justify-content: center; min-height: 165px; margin: 0; color: #a5c5ed; font-size: 13px; }
.overview-data-warning { display: flex; gap: 10px; margin-bottom: 16px; padding: 10px 12px; border: 1px solid color-mix(in srgb, var(--ns-warning) 32%, var(--ns-border)); border-radius: 8px; background: color-mix(in srgb, var(--ns-warning) 7%, var(--ns-surface)); color: var(--ns-text-2); font-size: 12px; }
.overview-data-warning strong { color: var(--ns-warning); }
.overview-skeleton-kpis, .overview-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: var(--overview-gap); }
.overview-skeleton-kpis .el-skeleton__item { height: 126px; border-radius: 10px; }
.overview-skeleton-panels { display: grid; grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr); gap: var(--overview-gap); margin-top: var(--overview-gap); }
.overview-skeleton-panels .el-skeleton__item { height: 320px; border-radius: 10px; }
.overview-section { margin-bottom: 22px; }
.overview-section-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.overview-section-head h2 { margin: 0; color: var(--ns-text); font-size: 15px; font-weight: 650; letter-spacing: -.01em; }
.overview-section-head p { margin: 4px 0 0; color: var(--ns-text-3); font-size: 12px; }
.overview-section-index { color: var(--ns-text-3); font-size: 12px; letter-spacing: .06em; }
.overview-kpis :deep(.metric-card) { position: relative; overflow: hidden; min-height: 132px; border-radius: 12px; padding: 18px 20px; background: linear-gradient(130deg, color-mix(in srgb, var(--metric-accent, var(--ns-border-strong)) 4%, var(--ns-surface)), var(--ns-surface) 70%); }
.overview-kpis :deep(.metric-card::before) { height: 26px; top: 20px; border-radius: 0 2px 2px 0; }
.overview-kpis :deep(.metric-card-value) { font-size: 34px; margin-top: 9px; }
.overview-kpis :deep(.metric-card-label) { padding-right: 28px; }
.overview-kpis :deep(.metric-card-hint) { line-height: 1.5; }
.overview-metric-icon { position: absolute; right: 18px; top: 17px; display: flex; color: var(--metric-accent); opacity: .75; }
.overview-metric-icon svg { width: 24px; height: 24px; }
.overview-health-status { display: block; font-size: 21px; line-height: 1.4; }
.overview-health-segments { display: flex; gap: 3px; margin: 9px 0 4px; }
.overview-health-segments i { height: 4px; flex: 1; border-radius: 2px; background: var(--ns-border-strong); }
.overview-health-segments i[data-state='up'] { background: var(--ns-success); }
.overview-health-segments i[data-state='down'] { background: var(--ns-danger); }
.overview-work-grid { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(280px, .85fr); gap: var(--overview-gap); margin-bottom: 18px; align-items: start; }
.overview-page :deep(.ov-card) { border-radius: 12px; overflow: hidden; }
.overview-page :deep(.ov-card .el-card__header) { background: transparent; padding: 18px 20px; }
.overview-page :deep(.ov-card .el-card__body) { padding: 16px 20px; }
.ov-card-head { width: 100%; }
.ov-card-head > div { display: flex; flex-direction: column; gap: 5px; }
.overview-panel-title { display: flex; align-items: center; gap: 8px; }
.overview-panel-mark { width: 6px; height: 14px; border-radius: 2px; background: var(--ns-accent); box-shadow: 3px 0 0 color-mix(in srgb, var(--ns-accent) 18%, transparent); }
.overview-card-hint { color: var(--ns-text-3); font-size: 12px; font-weight: 400; }
.overview-priority-list { display: flex; flex-direction: column; }
.overview-priority-item { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto 48px 16px; align-items: center; gap: 12px; width: 100%; min-height: 64px; padding: 10px 6px; border: 0; border-bottom: 1px solid var(--ns-border); background: transparent; color: var(--ns-text); cursor: pointer; font: inherit; text-align: left; border-radius: 6px; }
.overview-priority-item:last-child { border-bottom: 0; }
.overview-priority-item:hover { background: var(--ns-surface-muted); }
.overview-priority-item:focus-visible, .overview-actions button:focus-visible { outline: 2px solid var(--ns-accent); outline-offset: 2px; }
.overview-priority-rank { display: grid; place-items: center; width: 26px; height: 26px; border: 1px solid var(--ns-border); border-radius: 6px; color: var(--ns-text-3); font-size: 12px; }
.overview-priority-copy { min-width: 0; }
.overview-priority-copy strong, .overview-priority-copy span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.overview-priority-copy strong { font-size: 14px; font-weight: 600; }
.overview-priority-copy span { margin-top: 3px; color: var(--ns-text-3); font-size: 12px; }
.overview-priority-score { color: var(--ns-text-2); font-size: 18px; font-weight: 650; text-align: right; }
.overview-priority-score small { display: block; margin-bottom: 2px; font: 12px/1.4 var(--ns-font-ui); color: var(--ns-text-3); }
.overview-priority-score.is-high { color: var(--ns-danger); }
.overview-priority-arrow { color: var(--ns-text-3); }
.overview-actions { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin-bottom: 24px; }
.overview-actions button { position: relative; display: grid; grid-template-columns: 32px minmax(0, 1fr); gap: 4px 12px; width: 100%; padding: 16px 28px 16px 16px; border: 1px solid var(--ns-border); border-radius: 10px; background: var(--ns-surface); color: var(--ns-text); cursor: pointer; font: inherit; text-align: left; transition: border-color .16s, background .16s; }
.overview-actions button:hover { border-color: var(--ns-accent-line); background: var(--ns-accent-subtle); }
.overview-action-icon { grid-row: 1 / 3; align-self: center; display: grid; place-items: center; width: 32px; height: 32px; border-radius: 8px; background: var(--ns-accent-subtle); color: var(--ns-accent-fg); }
.overview-action-icon svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linejoin: round; stroke-linecap: round; }
.overview-action-arrow { position: absolute; right: 12px; top: 15px; color: var(--ns-text-3); }
.overview-actions strong { font-size: 13px; font-weight: 650; }
.overview-actions small { color: var(--ns-text-3); font-size: 12px; line-height: 1.5; }
.overview-severity-list { display: grid; gap: 16px; padding: 4px 0; }
.overview-severity-label { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; margin-bottom: 7px; color: var(--ns-text-2); font-size: 12px; }
.overview-severity-label > span { display: flex; align-items: center; gap: 8px; }
.overview-severity-label i { width: 6px; height: 6px; border-radius: 2px; background: var(--level-color); }
.overview-severity-label strong { color: var(--ns-text); font-size: 14px; font-weight: 600; }
.overview-severity-track { height: 5px; overflow: hidden; border-radius: 3px; background: var(--ns-border); }
.overview-severity-fill { display: block; height: 100%; border-radius: inherit; background: var(--level-color); }
.overview-lower-grid { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(290px, .7fr); gap: var(--overview-gap); }
.overview-health-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.overview-health-item { display: grid; grid-template-columns: 8px minmax(0, 1fr); align-items: center; gap: 3px 8px; padding: 10px; border: 1px solid var(--ns-border); border-radius: 7px; color: var(--ns-text-2); font-size: 12px; }
.overview-health-item > span:nth-child(2) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.overview-health-item small { grid-column: 2; color: var(--ns-text-2); font-size: 12px; }
.overview-health-item small.up { color: var(--ns-success); }
.overview-health-item small.down { color: var(--ns-danger); }
.ov-chip-dot.unknown { background: var(--ns-text-3); }
@media (max-width: 1100px) { .overview-work-grid { grid-template-columns: minmax(0, 1.4fr) minmax(240px, .8fr); }.overview-lower-grid { grid-template-columns: 1fr; }.overview-kpis { gap: 10px; }.overview-kpis :deep(.metric-card) { padding: 16px 14px; }.overview-metric-icon { right: 12px; } }
@media (max-width: 760px) {
  .overview-hero { grid-template-columns: 1fr; gap: 18px; padding: 22px; margin-bottom: 18px; }
  .overview-hero-caption { margin-bottom: 10px; }
  .overview-hero-total { font-size: 42px; }
  .overview-hero-trend { padding: 14px 0 0; border-left: 0; border-top: 1px solid #739bc329; }
  .overview-hero-chart, .overview-hero-empty { height: 130px !important; min-height: 130px; }
  .overview-skeleton-kpis, .overview-work-grid { grid-template-columns: 1fr; }
  .overview-skeleton-panels { grid-template-columns: 1fr; }
  .overview-section-head { align-items: flex-start; flex-direction: column; }
  .overview-priority-item { grid-template-columns: 24px minmax(0, 1fr) auto; gap: 8px; }
  .overview-priority-score, .overview-priority-arrow { display: none; }
}
.overview-priority-card :deep(.empty-state) { min-height: 110px; padding: 18px; }
@media (max-width: 720px) {
  .overview-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .overview-kpis :deep(.metric-card) { padding: 14px 12px; min-height: 112px; }
  .overview-kpis :deep(.metric-card-value) { font-size: 23px; margin-top: 4px; }
  .overview-kpis :deep(.metric-card-hint) { margin-top: 3px; }
  .overview-metric-icon { display: none; }
  .overview-kpis :deep(.metric-card-label) { padding-right: 0; }
  .overview-section-index { display: none; }
  .overview-section-head p { line-height: 1.6; }
  .overview-actions { grid-template-columns: 1fr 1fr; gap: 8px; }
  .overview-actions button { grid-template-columns: 1fr; padding: 14px; }
  .overview-action-icon { grid-row: auto; margin-bottom: 4px; }
  .overview-section { margin-bottom: 12px; }
  .overview-page :deep(.page-header) { margin-bottom: 12px; }
}
</style>
