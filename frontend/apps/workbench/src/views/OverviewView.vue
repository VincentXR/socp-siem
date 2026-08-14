<script setup lang="ts">
/**
 * 概览页（2026-08-14 按 Apple 风设计稿重构）：
 * 页头(标题/副标题/操作) + 3 张 KPI 卡 + 近7日趋势/级别分布 + 最需处置 Top5/最新告警 + 后端服务健康芯片。
 * 数据全部来自真实接口：stat/sitStats/filteredAlarms(alarms)/healths。
 */
import { computed, ref } from 'vue'
import AnimatedNumber from '../AnimatedNumber.vue'
import TrendChart from '../components/TrendChart.vue'
import SevBadge from '../components/SevBadge.vue'
import { sevColor } from '../lib/ui'
import { HEALTH_TARGETS } from '../api'
import type { Alarm } from '../api'

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
function onRefresh() { updatedAt.value = now(); emit('refresh') }

const trendSum = computed(() => Object.values(props.sitStats?.trend7d ?? {}).reduce((a, b) => a + b, 0))
const highPending = computed(() => props.stat.critical + props.stat.high)
const onlinePct = computed(() => (HEALTH_TARGETS.length ? Math.round((props.stat.online / HEALTH_TARGETS.length) * 100) : 0))
const maxLevel = computed(() => Math.max(1, ...LEVELS.map(l => props.sitStats?.bySeverity?.[l] ?? 0)))
const topRisk = computed(() => (props.sitStats?.topRisk ?? []).slice(0, 5))
const latestAlarms = computed(() => props.filteredAlarms.slice(0, 5))
const timeOnly = (iso: string) => (iso?.length >= 19 ? iso.slice(11, 19) : '—')
</script>

<template>
  <div class="page-pad view-enter">
    <!-- 页头 + 操作 -->
    <div class="ov-head">
      <div>
        <div class="ov-title">安全概览</div>
        <div class="ov-sub">实时态势 · 最近更新 {{ updatedAt }} · 数据周期 24h</div>
      </div>
      <div class="ov-actions">
        <span class="ov-date-pill">最近 24 小时 ▾</span>
        <el-button type="primary" size="small" round @click="onRefresh">刷新</el-button>
      </div>
    </div>

    <!-- KPI 三卡 -->
    <div class="ov-kpis">
      <div class="ov-kpi">
        <div class="ov-kpi-label">告警总数</div>
        <div class="ov-kpi-value"><AnimatedNumber :value="stat.total" /></div>
        <div class="ov-kpi-delta">近 7 日累计 <b class="mono">{{ trendSum }}</b> 条</div>
      </div>
      <div class="ov-kpi">
        <div class="ov-kpi-label">高危待处置</div>
        <div class="ov-kpi-value" style="color:#f56c6c"><AnimatedNumber :value="highPending" /></div>
        <div class="ov-kpi-delta">CRITICAL <b class="mono">{{ stat.critical }}</b> · HIGH <b class="mono">{{ stat.high }}</b></div>
      </div>
      <div class="ov-kpi">
        <div class="ov-kpi-label">服务在线率</div>
        <div class="ov-kpi-value" style="color:#30d158"><AnimatedNumber :value="onlinePct" /><span style="font-size:22px;font-weight:600">%</span></div>
        <div class="ov-kpi-delta">{{ stat.online }} / {{ HEALTH_TARGETS.length }} 服务正常</div>
      </div>
    </div>

    <!-- 近 7 日趋势 + 告警级别分布 -->
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
          <div v-for="s in LEVELS" :key="s" class="ov-level-seg"
            :style="{ flex: (sitStats?.bySeverity?.[s] ?? 0) / maxLevel + 0.02, background: sevColor(s) }"
            :title="`${s}: ${sitStats?.bySeverity?.[s] ?? 0}`" />
        </div>
        <div class="ov-level-legend">
          <span v-for="s in LEVELS" :key="s" class="ov-level-item">
            <i class="ov-level-dot" :style="{ background: sevColor(s) }" />{{ s }}
            <b class="mono">{{ sitStats?.bySeverity?.[s] ?? 0 }}</b>
          </span>
        </div>
      </el-card>
    </div>

    <!-- 最需处置 Top5 + 最新告警 -->
    <div class="ov-low">
      <el-card shadow="never" class="ov-card">
        <template #header><span>最需处置 Top 5</span></template>
        <div v-if="topRisk.length" class="ov-risk">
          <div v-for="(r, i) in topRisk" :key="r.id" class="ov-risk-item">
            <span class="ov-rank mono">{{ i + 1 }}</span>
            <div class="ov-risk-body">
              <div class="ov-risk-name">{{ r.ruleName }}</div>
              <div class="ov-risk-entity mono">{{ r.entity }}</div>
            </div>
            <span class="ov-risk-score mono" :style="{ background: sevColor(r.severity) }">{{ r.riskScore ?? '—' }}</span>
          </div>
        </div>
        <div v-else class="feed-empty">暂无风险告警</div>
      </el-card>

      <el-card shadow="never" class="ov-card">
        <template #header><span>最新告警</span></template>
        <el-table :data="latestAlarms" size="small">
          <el-table-column label="时间" width="96">
            <template #default="{ row }"><span class="mono">{{ timeOnly(row.occurredAt) }}</span></template>
          </el-table-column>
          <el-table-column label="级别" width="116">
            <template #default="{ row }"><SevBadge :value="row.severity" /></template>
          </el-table-column>
          <el-table-column label="规则 · 源" min-width="180">
            <template #default="{ row }">
              <span class="ov-alert-rule">{{ row.ruleName }}</span><span class="ov-alert-entity mono"> · {{ row.entity }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <span class="ov-alert-status" :data-s="row.status">{{ STATUS_TEXT[row.status] ?? row.status }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 后端服务健康 -->
    <el-card shadow="never" class="ov-card">
      <template #header>
        <div class="ov-card-head"><span>后端服务健康</span><span class="ov-card-sub">{{ stat.online }} / {{ HEALTH_TARGETS.length }} 在线</span></div>
      </template>
      <div class="ov-chips">
        <div v-for="h in HEALTH_TARGETS" :key="h.name" class="ov-chip">
          <span class="ov-chip-dot" :class="healths[h.name] === 'up' ? 'up' : 'down'" />
          <span class="ov-chip-name">{{ h.name }}</span>
        </div>
      </div>
    </el-card>
  </div>
</template>
