<script setup lang="ts">
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import { onMounted, ref } from 'vue'
import {
  appendWatchlist, deleteWatchlist, listWatchlists, putWatchlist,
  uebaEntities, uebaEntity, uebaScore, uebaSummary,
  type RiskEntity, type RiskSummary, type ScoreBreakdown, type Watchlist,
} from '../api'
import UebaEntityDrawer from '../components/ueba/UebaEntityDrawer.vue'
import UebaRiskPanel from '../components/ueba/UebaRiskPanel.vue'
import UebaScorePanel from '../components/ueba/UebaScorePanel.vue'
import UebaWatchlistsPanel from '../components/ueba/UebaWatchlistsPanel.vue'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'go-alarms': [entity: string] }>()

const riskEntities = ref<RiskEntity[]>([])
const riskSummary = ref<RiskSummary | null>(null)
const riskLimit = ref(20)
const entityDrawer = ref(false)
const entityDetail = ref<RiskEntity | null>(null)
const watchlists = ref<Watchlist[]>([])
const uebaTab = ref('entities')
const scoreForm = ref({ severity: 'HIGH', mitre: 'T1110', tiHits: 1, recentAlerts: 3, assetCriticality: 2 })
const scoreResult = ref<ScoreBreakdown | null>(null)

async function loadUeba() {
  const [entities, summary, lists] = await Promise.allSettled([uebaEntities(riskLimit.value), uebaSummary(), listWatchlists()])
  riskEntities.value = entities.status === 'fulfilled' ? entities.value : []
  riskSummary.value = summary.status === 'fulfilled' ? summary.value : null
  watchlists.value = lists.status === 'fulfilled' ? lists.value : []
  if (!scoreResult.value) await calcScore()
}

async function openEntity(entity: RiskEntity) {
  entityDetail.value = entity
  entityDrawer.value = true
  try { entityDetail.value = await uebaEntity(entity.entity) } catch { /* 列表快照继续可用 */ }
}

async function calcScore() {
  try { scoreResult.value = await uebaScore(scoreForm.value) } catch { scoreResult.value = null }
}

async function refreshWatchlists() { watchlists.value = await listWatchlists() }
async function createWatchlist(name: string, values: string[]) { await putWatchlist(name, values); await refreshWatchlists() }
async function appendToWatchlist(name: string, values: string[]) { await appendWatchlist(name, values); await refreshWatchlists() }
async function removeWatchlist(name: string) {
  if (!confirm(`确认删除观察名单“${name}”？其中的值也会一并删除。`)) return
  await deleteWatchlist(name)
  await refreshWatchlists()
}

function goToAlarms() {
  if (entityDetail.value) {
    entityDrawer.value = false
    emit('go-alarms', entityDetail.value.entity)
  }
}

onMounted(loadUeba)
</script>

<template>
  <div class="page-pad view-enter">
    <el-row :gutter="12" style="margin-bottom:14px">
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.entities ?? 0 }}</div><div class="label">画像实体数</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ riskSummary?.maxRisk ?? 0 }}</div><div class="label">最高风险分</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#e6a23c">{{ (riskSummary?.byLevel?.CRITICAL ?? 0) + (riskSummary?.byLevel?.HIGH ?? 0) }}</div><div class="label">高危实体</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.halfLifeHours ?? 0 }}h</div><div class="label">风险半衰期</div></div></el-card></el-col>
      <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ watchlists.length }}</div><div class="label">观察名单</div></div></el-card></el-col>
    </el-row>

    <el-tabs v-model="uebaTab">
      <el-tab-pane label="实体风险排名" name="entities">
        <UebaRiskPanel
          :theme="props.theme"
          :entities="riskEntities"
          :summary="riskSummary"
          :risk-limit="riskLimit"
          @update:risk-limit="riskLimit = $event"
          @refresh="loadUeba"
          @select="openEntity"
        />
      </el-tab-pane>
      <el-tab-pane label="观察名单" name="watchlists">
        <UebaWatchlistsPanel
          :watchlists="watchlists"
          @create="createWatchlist"
          @append="appendToWatchlist"
          @remove="removeWatchlist"
        />
      </el-tab-pane>
      <el-tab-pane label="评分模型试算" name="score">
        <UebaScorePanel :form="scoreForm" :result="scoreResult" @calculate="calcScore" />
      </el-tab-pane>
    </el-tabs>

    <UebaEntityDrawer v-model="entityDrawer" :entity="entityDetail" @go-alarms="goToAlarms" />
  </div>
</template>
