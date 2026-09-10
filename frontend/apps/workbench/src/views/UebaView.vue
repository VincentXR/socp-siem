<script setup lang="ts">
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ActionFeedback from '../components/ActionFeedback.vue'
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
import { useI18n } from '../composables/useI18n'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'go-alarms': [entity: string] }>()
const { t } = useI18n()

const loadError = ref('')
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
  loadError.value = [entities, summary, lists].filter(item => item.status === 'rejected').map(item => String((item as PromiseRejectedResult).reason)).join(' · ')
  if (entities.status === 'fulfilled') riskEntities.value = entities.value
  if (summary.status === 'fulfilled') riskSummary.value = summary.value
  if (lists.status === 'fulfilled') watchlists.value = lists.value
  if (!scoreResult.value) await calcScore()
}

async function openEntity(entity: RiskEntity) {
  entityDetail.value = entity
  entityDrawer.value = true
  try { entityDetail.value = await uebaEntity(entity.entity) } catch (failure) { loadError.value = String(failure) }
}

async function calcScore() {
  try { scoreResult.value = await uebaScore(scoreForm.value) } catch (failure) { loadError.value = String(failure) }
}

async function refreshWatchlists() { watchlists.value = await listWatchlists() }
async function createWatchlist(name: string, values: string[]) { await putWatchlist(name, values); await refreshWatchlists() }
async function appendToWatchlist(name: string, values: string[]) { await appendWatchlist(name, values); await refreshWatchlists() }
async function removeWatchlist(name: string) {
  if (!confirm(t('ueba.deleteWatchlistConfirm', { name }))) return
  try { await deleteWatchlist(name); await refreshWatchlists() }
  catch (failure) { loadError.value = String(failure) }
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
    <ActionFeedback :error="loadError" />
    <el-row :gutter="12" style="margin-bottom:14px">
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.entities ?? '—' }}</div><div class="label">{{ t('ueba.entityCount') }}</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-danger)">{{ riskSummary?.maxRisk ?? '—' }}</div><div class="label">{{ t('ueba.maxRisk') }}</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-warning)">{{ (riskSummary?.byLevel?.CRITICAL ?? 0) + (riskSummary?.byLevel?.HIGH ?? 0) }}</div><div class="label">{{ t('ueba.highRiskEntities') }}</div></div></el-card></el-col>
      <el-col :span="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.halfLifeHours ?? 0 }}h</div><div class="label">{{ t('ueba.halfLife') }}</div></div></el-card></el-col>
      <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-accent-fg)">{{ watchlists.length }}</div><div class="label">{{ t('ueba.watchlists') }}</div></div></el-card></el-col>
    </el-row>

    <el-tabs v-model="uebaTab">
      <el-tab-pane :label="t('ueba.entityRanking')" name="entities">
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
      <el-tab-pane :label="t('ueba.watchlists')" name="watchlists">
        <UebaWatchlistsPanel
          :watchlists="watchlists"
          :create="createWatchlist"
          :append="appendToWatchlist"
          @remove="removeWatchlist"
        />
      </el-tab-pane>
      <el-tab-pane :label="t('ueba.scoreSimulation')" name="score">
        <UebaScorePanel :form="scoreForm" :result="scoreResult" @calculate="calcScore" />
      </el-tab-pane>
    </el-tabs>

    <UebaEntityDrawer v-model="entityDrawer" :entity="entityDetail" @go-alarms="goToAlarms" />
  </div>
</template>
