<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { HEALTH_TARGETS, checkHealth, gasEngineStats, ingestSummary, type GasStats, type IngestSummary } from '../api'

type HealthItem = (typeof HEALTH_TARGETS)[number] & { status: string }

const healthList = ref<HealthItem[]>([])
const healthEngine = ref<GasStats | null>(null)
const healthIngest = ref<IngestSummary | null>(null)
const loading = ref(false)
let refreshTimer: number | undefined

const healthUpCount = computed(() => healthList.value.filter(service => service.status === 'up').length)

async function loadHealth() {
  if (loading.value) return
  loading.value = true
  try {
    const [health, engine, ingest] = await Promise.allSettled([
      Promise.all(HEALTH_TARGETS.map(async target => ({ ...target, status: await checkHealth(target.path) }))),
      gasEngineStats(),
      ingestSummary(),
    ])
    healthList.value = health.status === 'fulfilled'
      ? health.value
      : HEALTH_TARGETS.map(target => ({ ...target, status: 'down' }))
    if (engine.status === 'fulfilled') healthEngine.value = engine.value
    if (ingest.status === 'fulfilled') healthIngest.value = ingest.value
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadHealth()
  refreshTimer = window.setInterval(loadHealth, 30_000)
})
onUnmounted(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="系统健康" description="监控后端服务、检测引擎和日志接入链路的实时状态。">
      <template #actions><el-button type="primary" size="small" :loading="loading" @click="loadHealth">刷新</el-button></template>
    </PageHeader>

    <div class="health-kpis">
      <MetricCard label="服务在线" :tone="healthList.length && healthUpCount === healthList.length ? 'success' : 'danger'">
        {{ healthUpCount }} / {{ healthList.length }}
      </MetricCard>
      <MetricCard label="引擎累计事件" tone="info">{{ healthEngine?.eventCount ?? 0 }}</MetricCard>
      <MetricCard label="引擎累计告警" tone="danger">{{ healthEngine?.alertCount ?? 0 }}</MetricCard>
      <MetricCard label="接入 EPS(1m)" tone="info">{{ healthIngest?.eps1m ?? 0 }}</MetricCard>
    </div>

    <el-card shadow="never" class="health-card">
      <template #header><span>服务健康 · 30s 自动刷新</span></template>
      <div class="health-grid">
        <div v-for="service in healthList" :key="service.name" class="health-service">
          <span class="health-service-name">{{ service.name }}</span>
          <span class="mono health-service-path">{{ service.path }}</span>
          <span class="health-service-status" :class="service.status === 'up' ? 'is-up' : 'is-down'">
            <i /> {{ service.status === 'up' ? 'UP' : 'DOWN' }}
          </span>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="health-card health-ops-card">
      <template #header>运维信息</template>
      <div class="health-ops-copy">
        引擎队列负载 <b class="mono">{{ healthEngine?.queueLoad ?? 0 }}</b> · 丢弃 <b class="mono">{{ healthEngine?.dropCount ?? 0 }}</b> ·
        抑制 <b class="mono">{{ healthEngine?.suppressedCount ?? 0 }}</b> · 规则 <b class="mono">{{ healthEngine?.rules ?? 0 }}</b> 条<br>
        服务日志位于 <span class="mono">.cache/&lt;服务名&gt;.log</span>（运行目录）；Prometheus 指标：<span class="mono">/{服务名}/actuator/prometheus</span>
      </div>
    </el-card>
  </div>
</template>
