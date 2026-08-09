<script setup lang="ts">
/**
 * 概览页（2026-08-10 从 App.vue 拆分）：主数字 Hero + 级别分布 + 趋势 + 风险 Top + 最新告警 + 服务健康。
 * 数据由 App.vue 通过 props 传入（Vue props 响应式），纯展示。
 */
import AnimatedNumber from '../AnimatedNumber.vue'
import TrendChart from '../components/TrendChart.vue'
import SevBadge from '../components/SevBadge.vue'
import { sevColor } from '../lib/ui'
import { HEALTH_TARGETS } from '../api'

defineProps<{
  stat: { total: number; critical: number; high: number; online: number }
  sitStats?: {
    trend7d?: Record<string, number>
    bySeverity?: Record<string, number>
    topRisk?: Array<{ id: string; ruleName: string; entity: string; severity: string; riskScore?: number }>
  } | null
  filteredAlarms: Array<{ id: string; severity: string; ruleName: string; entity: string; message: string }>
  healths: Record<string, string>
}>()
</script>

<template>
  <div class="page-pad view-enter">
    <!-- 主数字 Hero：总览焦点 -->
    <div class="ov-hero">
      <div class="ov-hero-main">
        <div class="ov-hero-num"><AnimatedNumber :value="stat.total" /></div>
        <div class="ov-hero-label">告警总数</div>
        <div class="ov-hero-sub" v-if="sitStats">较昨日趋势
          <span>{{ Object.keys(sitStats?.trend7d ?? {}).length }} 天趋势可用</span>
        </div>
      </div>
      <div class="ov-hero-side">
        <div class="ov-side-item">
          <div class="ov-side-num" style="color:#f85149"><AnimatedNumber :value="stat.critical + stat.high" /></div>
          <div class="ov-side-label">高危告警（CRITICAL+HIGH）</div>
        </div>
        <div class="ov-side-item">
          <div class="ov-side-num" style="color:#30d158">{{ stat.online }}/11</div>
          <div class="ov-side-label">服务在线</div>
        </div>
      </div>
    </div>

    <!-- severity 色带分布 -->
    <el-card shadow="never" style="margin-top:14px" v-if="sitStats">
      <template #header>告警级别分布</template>
      <div class="sev-band">
        <div v-for="s in ['CRITICAL','HIGH','MEDIUM','LOW']" :key="s"
          class="sev-seg" :style="{ flex: (sitStats.bySeverity?.[s] ?? 0) + 0.01, background: sevColor(s) }"
          :title="`${s}: ${sitStats.bySeverity?.[s] ?? 0}`" />
      </div>
      <div class="sev-legend">
        <span v-for="s in ['CRITICAL','HIGH','MEDIUM','LOW']" :key="s" class="sev-legend-item">
          <i class="sev-legend-dot" :style="{ background: sevColor(s) }" />{{ s }}
          <b class="mono">{{ sitStats.bySeverity?.[s] ?? 0 }}</b>
        </span>
      </div>
    </el-card>

    <el-row :gutter="14" style="margin-top:14px">
      <!-- 7 日趋势 -->
      <el-col :span="16">
        <el-card shadow="never" style="height:100%">
          <template #header>近 7 日告警趋势</template>
          <TrendChart :data="sitStats?.trend7d" style="height:210px" />
        </el-card>
      </el-col>
      <!-- 风险 Top -->
      <el-col :span="8">
        <el-card shadow="never" style="height:100%">
          <template #header>最需处置</template>
          <div v-if="(sitStats?.topRisk ?? []).length" class="ov-risk">
            <div v-for="r in (sitStats?.topRisk ?? []).slice(0, 5)" :key="r.id" class="ov-risk-item">
              <span class="feed-dot" :style="{ background: sevColor(r.severity) }" />
              <div class="ov-risk-body">
                <div class="ov-risk-name">{{ r.ruleName }}</div>
                <div class="ov-risk-entity mono">{{ r.entity }}</div>
              </div>
              <span class="ov-risk-score mono">{{ r.riskScore ?? '—' }}</span>
            </div>
          </div>
          <div v-else class="feed-empty">暂无风险告警</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="14" style="margin-top:14px">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>最新告警</template>
          <el-table :data="filteredAlarms.slice(0, 5)" size="small">
            <el-table-column label="级别" width="100"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
            <el-table-column prop="ruleName" label="规则" min-width="150" />
            <el-table-column prop="entity" label="实体" width="120" />
            <el-table-column prop="message" label="消息" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>后端服务健康</template>
          <div class="ov-health">
            <div v-for="h in HEALTH_TARGETS" :key="h.name" class="ov-health-item">
              <span class="ov-health-dot" :class="healths[h.name] === 'up' ? 'up' : 'down'" />
              <span class="ov-health-name">{{ h.name }}</span>
              <span class="ov-health-state" :class="healths[h.name] === 'up' ? 'up' : 'down'">{{ healths[h.name] === 'up' ? 'UP' : 'DOWN' }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
