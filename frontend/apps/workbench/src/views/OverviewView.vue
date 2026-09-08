<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed, ref } from 'vue'
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
  error?: string
  goAlarms?: (query?: Record<string, string>) => void
  openAlarm?: (id: string) => void
  goCases?: () => void
}>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

const { t, d } = useI18n()

const LEVELS = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const

const now = () => d(new Date(), 'time')
const updatedAt = ref(now())
function onRefresh() {
  updatedAt.value = now()
  emit('refresh')
}

const trendSum = computed(() => Object.values(props.sitStats?.trend7d ?? {}).reduce((a, b) => a + b, 0))
const highPending = computed(() => props.stat.critical + props.stat.high)
const maxLevel = computed(() => Math.max(1, ...LEVELS.map(level => props.sitStats?.bySeverity?.[level] ?? 0)))
const topRisk = computed(() => (props.sitStats?.topRisk ?? []).slice(0, 5))
const latestAlarms = computed(() => props.filteredAlarms.slice(0, 5))
function openAllAlarms(): void { props.goAlarms?.() }
function openHighRiskAlarms(): void { props.goAlarms?.({ severity: 'HIGH' }) }
function openCases(): void { props.goCases?.() }
function openRecentAlarm(row: unknown): void {
  const id = (row as Alarm)?.id
  if (id) props.openAlarm?.(id)
}
const timeOnly = (iso: string) => (iso?.length >= 19 ? iso.slice(11, 19) : '—')

function getStatusLabel(status: string): string {
  return t('statuses.' + status) || status
}
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.overview')" :title="t('overview.title')">
      <template #description>{{ t('overview.description') }} · {{ updatedAt }}</template>
      <template #actions>
        <span class="ov-date-pill">{{ t('overview.last7Days') }}</span>
        <el-button type="primary" size="small" round @click="onRefresh">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>

    <div v-if="props.error" class="overview-data-warning" role="alert">
      <strong>{{ t('overview.dataUnavailable') }}</strong>
      <span>{{ props.error }}</span>
    </div>
    <div v-if="props.loading" class="overview-loading" aria-live="polite">{{ t('common.loading') }}</div>

    <div class="ov-kpis">
      <MetricCard :label="t('overview.totalAlarms7d')" tone="info" :interactive="Boolean(props.goAlarms)" @click="openAllAlarms">
        <AnimatedNumber :value="stat.total" />
        <template #hint>{{ t('overview.alarmWindow7d') }} · {{ t('overview.sourceAlarmStats') }}</template>
      </MetricCard>
      <MetricCard :label="t('overview.highCriticalAlarms7d')" tone="danger" :interactive="Boolean(props.goAlarms)" @click="openHighRiskAlarms">
        <AnimatedNumber :value="highPending" />
        <template #hint>CRITICAL <b class="mono">{{ stat.critical }}</b> · HIGH <b class="mono">{{ stat.high }}</b></template>
      </MetricCard>
      <MetricCard :label="t('overview.activeCases')" tone="warning" :interactive="Boolean(props.goCases)" @click="openCases">
        <AnimatedNumber :value="stat.activeCases" />
        <template #hint>{{ t('overview.activeCasesHint') }}</template>
      </MetricCard>
    </div>

    <div class="ov-mid">
      <el-card shadow="never" class="ov-card">
        <template #header>
          <div class="ov-card-head"><span>{{ t('overview.alarmTrend') }}</span><span class="ov-card-sub">{{ t('overview.dailyTotal', { total: trendSum }) }}</span></div>
        </template>
        <TrendChart :data="sitStats?.trend7d" style="height:216px" />
      </el-card>

      <el-card shadow="never" class="ov-card">
        <template #header><span>{{ t('overview.sevDistribution') }}</span></template>
        <div class="ov-level-bar">
          <div
            v-for="level in LEVELS"
            :key="level"
            class="ov-level-seg"
            :style="{ flex: (sitStats?.bySeverity?.[level] ?? 0) / maxLevel + 0.02, background: sevColor(level) }"
            :title="`${level}: ${sitStats?.bySeverity?.[level] ?? 0}`"
          />
        </div>
        <div class="ov-level-legend">
          <span v-for="level in LEVELS" :key="level" class="ov-level-item">
            <i class="ov-level-dot" :style="{ background: sevColor(level) }" />{{ level }}
            <b class="mono">{{ sitStats?.bySeverity?.[level] ?? 0 }}</b>
          </span>
        </div>
      </el-card>
    </div>

    <div class="ov-low">
      <el-card shadow="never" class="ov-card">
        <template #header><span>Top 5 {{ t('overview.riskEntities') }}</span></template>
        <div v-if="topRisk.length" class="ov-risk">
            <div v-for="(risk, index) in topRisk" :key="risk.id" class="ov-risk-item" role="button" tabindex="0" @click="openRecentAlarm(risk)" @keydown.enter.prevent="openRecentAlarm(risk)">
            <span class="ov-rank mono">{{ index + 1 }}</span>
            <div class="ov-risk-body">
              <div class="ov-risk-name">{{ risk.ruleName }}</div>
              <div class="ov-risk-entity mono">{{ risk.entity }}</div>
            </div>
            <span class="ov-risk-score mono" :class="`risk-${String(risk.severity || 'INFO').toLowerCase()}`">{{ risk.riskScore ?? '—' }}</span>
          </div>
        </div>
        <EmptyState v-else-if="!props.loading" :title="t('overview.noHighRiskAlarms')" :description="t('overview.noUrgentRiskItems')" />
      </el-card>

      <el-card shadow="never" class="ov-card">
        <template #header><span>{{ t('overview.recentAlarms') }}</span></template>
        <div v-if="latestAlarms.length" class="ov-alert-table">
          <el-table :data="latestAlarms" size="small" @row-click="openRecentAlarm">
            <el-table-column :label="t('common.timestamp')" width="96">
              <template #default="{ row }"><span class="mono">{{ timeOnly(row.occurredAt) }}</span></template>
            </el-table-column>
            <el-table-column :label="t('common.severity')" width="116">
              <template #default="{ row }"><SevBadge :value="row.severity" /></template>
            </el-table-column>
            <el-table-column :label="t('overview.ruleEntity')" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="table-text"><span class="ov-alert-rule">{{ row.ruleName }}</span><span class="ov-alert-entity mono"> · {{ row.entity }}</span></span>
              </template>
            </el-table-column>
            <el-table-column :label="t('common.status')" width="92">
              <template #default="{ row }"><span class="ov-alert-status" :data-s="row.status">{{ getStatusLabel(row.status) }}</span></template>
            </el-table-column>
          </el-table>
        </div>
        <EmptyState v-else-if="!props.loading" :title="t('overview.noLiveAlarms')" :description="t('overview.alarmsWillAppear')" />
      </el-card>
    </div>

    <el-card shadow="never" class="ov-card">
      <template #header>
        <div class="ov-card-head"><span>{{ t('overview.platformServiceHealth') }}</span><span class="ov-card-sub">{{ stat.online }} / {{ HEALTH_TARGETS.length }} {{ t('overview.healthy') }}</span></div>
      </template>
      <div class="ov-chips">
        <div v-for="health in HEALTH_TARGETS" :key="health.name" class="ov-chip">
          <span class="ov-chip-dot" :class="healths[health.name] === 'up' ? 'up' : 'down'" />
          <span class="ov-chip-name">{{ health.name }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>
