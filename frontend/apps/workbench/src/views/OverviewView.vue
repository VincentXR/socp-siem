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

const props = defineProps<{
  stat: { total: number; critical: number; high: number; online: number }
  sitStats?: {
    trend7d?: Record<string, number>
    bySeverity?: Record<string, number>
    topRisk?: Array<{ id: string; ruleName: string; entity: string; severity: string; riskScore?: number; mitre?: string | null }>
  } | null
  filteredAlarms: Alarm[]
  healths: Record<string, string>
}>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

const LEVELS = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as const
const STATUS_TEXT: Record<string, string> = { OPEN: '待处置', INVESTIGATING: '研判中', RESOLVED: '已闭环', CLOSED: '已忽略' }

const now = () => new Date().toLocaleTimeString('zh-CN', { hour12: false })
const updatedAt = ref(now())
function onRefresh() {
  updatedAt.value = now()
  emit('refresh')
}

const trendSum = computed(() => Object.values(props.sitStats?.trend7d ?? {}).reduce((a, b) => a + b, 0))
const highPending = computed(() => props.stat.critical + props.stat.high)
const onlinePct = computed(() => (HEALTH_TARGETS.length ? Math.round((props.stat.online / HEALTH_TARGETS.length) * 100) : 0))
const maxLevel = computed(() => Math.max(1, ...LEVELS.map(level => props.sitStats?.bySeverity?.[level] ?? 0)))
const topRisk = computed(() => (props.sitStats?.topRisk ?? []).slice(0, 5))
const latestAlarms = computed(() => props.filteredAlarms.slice(0, 5))
const timeOnly = (iso: string) => (iso?.length >= 19 ? iso.slice(11, 19) : '—')
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="安全概览">
      <template #description>实时态势 · 最近更新 {{ updatedAt }} · 数据周期 24h</template>
      <template #actions>
        <span class="ov-date-pill">最近 24 小时</span>
        <el-button type="primary" size="small" round @click="onRefresh">刷新</el-button>
      </template>
    </PageHeader>

    <div class="ov-kpis">
      <MetricCard label="告警总数" tone="info">
        <AnimatedNumber :value="stat.total" />
        <template #hint>近 7 日累计 <b class="mono">{{ trendSum }}</b> 条</template>
      </MetricCard>
      <MetricCard label="高危待处置" tone="danger">
        <AnimatedNumber :value="highPending" />
        <template #hint>CRITICAL <b class="mono">{{ stat.critical }}</b> · HIGH <b class="mono">{{ stat.high }}</b></template>
      </MetricCard>
      <MetricCard label="服务在线率" tone="success">
        <AnimatedNumber :value="onlinePct" /><span class="metric-suffix">%</span>
        <template #hint>{{ stat.online }} / {{ HEALTH_TARGETS.length }} 个服务正常</template>
      </MetricCard>
    </div>

    <div class="ov-mid">
      <el-card shadow="never" class="ov-card">
        <template #header>
          <div class="ov-card-head"><span>近 7 日告警趋势</span><span class="ov-card-sub">按日聚合 · 总量 {{ trendSum }}</span></div>
        </template>
        <TrendChart :data="sitStats?.trend7d" style="height:216px" />
      </el-card>

      <el-card shadow="never" class="ov-card">
        <template #header><span>告警级别分布</span></template>
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
        <template #header><span>最需处置 Top 5</span></template>
        <div v-if="topRisk.length" class="ov-risk">
          <div v-for="(risk, index) in topRisk" :key="risk.id" class="ov-risk-item">
            <span class="ov-rank mono">{{ index + 1 }}</span>
            <div class="ov-risk-body">
              <div class="ov-risk-name">{{ risk.ruleName }}</div>
              <div class="ov-risk-entity mono">{{ risk.entity }}</div>
            </div>
            <span class="ov-risk-score mono" :style="{ background: sevColor(risk.severity) }">{{ risk.riskScore ?? '—' }}</span>
          </div>
        </div>
        <EmptyState v-else title="暂无高风险告警" description="当前没有需要优先处置的风险项" />
      </el-card>

      <el-card shadow="never" class="ov-card">
        <template #header><span>最新告警</span></template>
        <div v-if="latestAlarms.length" class="ov-alert-table">
          <el-table :data="latestAlarms" size="small">
            <el-table-column label="时间" width="96">
              <template #default="{ row }"><span class="mono">{{ timeOnly(row.occurredAt) }}</span></template>
            </el-table-column>
            <el-table-column label="级别" width="116">
              <template #default="{ row }"><SevBadge :value="row.severity" /></template>
            </el-table-column>
            <el-table-column label="规则 · 来源" min-width="180">
              <template #default="{ row }">
                <span class="ov-alert-rule">{{ row.ruleName }}</span><span class="ov-alert-entity mono"> · {{ row.entity }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }"><span class="ov-alert-status" :data-s="row.status">{{ STATUS_TEXT[row.status] ?? row.status }}</span></template>
            </el-table-column>
          </el-table>
        </div>
        <EmptyState v-else title="暂无最新告警" description="告警进入系统后会显示在这里" />
      </el-card>
    </div>

    <el-card shadow="never" class="ov-card">
      <template #header>
        <div class="ov-card-head"><span>后端服务健康</span><span class="ov-card-sub">{{ stat.online }} / {{ HEALTH_TARGETS.length }} 在线</span></div>
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
