<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ActionFeedback from '../components/ActionFeedback.vue'
import PageHeader from '../components/PageHeader.vue'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRequest } from '../composables/useRequest'
import {
  ApiError, appendWatchlist, createWatchlist as createWatchlistOnly, deleteWatchlist, getWatchlist, listWatchlists,
  listTechniques, uebaEntities, uebaEntity, uebaScore, uebaSummary,
  type RiskEntity, type RiskSummary, type ScoreBreakdown, type Watchlist, type WatchlistSummary,
} from '../api'
import UebaEntityDrawer from '../components/ueba/UebaEntityDrawer.vue'
import UebaRiskPanel from '../components/ueba/UebaRiskPanel.vue'
import UebaScorePanel from '../components/ueba/UebaScorePanel.vue'
import UebaWatchlistsPanel from '../components/ueba/UebaWatchlistsPanel.vue'
import { useI18n } from '../composables/useI18n'
import { useWriteAccess } from '../composables/useWriteAccess'
import { useConfirm } from '../composables/useConfirm'

const props = defineProps<{ theme: 'light' | 'dark' }>()
const emit = defineEmits<{ 'go-alarms': [entity: string] }>()
const { t } = useI18n()
const canWrite = useWriteAccess()
const { confirmDanger } = useConfirm()

const loadError = ref('')
const techniqueError = ref('')
const loading = ref(false)
const riskEntities = ref<RiskEntity[]>([])
const riskSummary = ref<RiskSummary | null>(null)
const riskLimit = ref(20)
const entityDrawer = ref(false)
const entityDetail = ref<RiskEntity | null>(null)
const entityRequest = useRequest<RiskEntity>()
const { loading: entityLoading, error: entityError } = entityRequest
const watchlists = ref<WatchlistSummary[]>([])
const uebaTab = ref('entities')
const scoreForm = ref({ severity: 'HIGH', mitre: 'T1110', tiHits: 1, recentAlerts: 3, assetCriticality: 2 })
const scoreRequest = useRequest<ScoreBreakdown>()
const { data: scoreResult, loading: scoreLoading, error: scoreError } = scoreRequest
const attackTechniques = ref<Array<{ id: string; name: string }>>([])
const techniquesLoading = ref(false)
let watchlistRevision = 0
let disposed = false

async function loadUeba() {
  if (loading.value) return
  loading.value = true
  techniquesLoading.value = true
  const loadedWatchlistRevision = watchlistRevision
  try {
  const [entities, summary, lists, techniques] = await Promise.allSettled([uebaEntities(riskLimit.value), uebaSummary(), listWatchlists(), listTechniques()])
  if (disposed) return
  techniqueError.value = techniques.status === 'rejected' ? String(techniques.reason) : ''
  if (techniques.status === 'fulfilled') attackTechniques.value = techniques.value.items.map(technique => ({ id: technique.id, name: technique.name }))
  loadError.value = [entities, summary, lists].filter(item => item.status === 'rejected').map(item => String((item as PromiseRejectedResult).reason)).join(' · ')
  if (entities.status === 'fulfilled') riskEntities.value = entities.value
  if (summary.status === 'fulfilled') riskSummary.value = summary.value
  if (lists.status === 'fulfilled' && loadedWatchlistRevision === watchlistRevision) watchlists.value = lists.value
  if (!scoreResult.value && !scoreLoading.value) void calcScore()
  } finally {
    techniquesLoading.value = false
    loading.value = false
  }
}

async function openEntity(entity: RiskEntity) {
  entityDetail.value = entity
  entityDrawer.value = true
  await loadEntityDetail()
}

async function loadEntityDetail() {
  const id = entityDetail.value?.entity
  if (!id || !entityDrawer.value) return
  const result = await entityRequest.execute(signal => uebaEntity(id, { signal }))
  if (result && entityDrawer.value && entityDetail.value?.entity === id) entityDetail.value = result
}

async function calcScore() {
  const input = { ...scoreForm.value }
  scoreRequest.reset()
  await scoreRequest.execute(signal => uebaScore(input, { signal }))
}

// Sliders change their model before committing a calculation. Hide the old
// answer immediately, including a response arriving during that interaction.
watch(scoreForm, () => scoreRequest.reset(), { deep: true, flush: 'sync' })
watch(entityDrawer, visible => { if (!visible) entityRequest.reset() }, { flush: 'sync' })
onUnmounted(() => { disposed = true; entityRequest.cancel(); scoreRequest.cancel() })

function acceptWatchlist(saved: Watchlist) {
  watchlistRevision++
  watchlists.value = [...watchlists.value.filter(item => item.name !== saved.name), { name: saved.name, size: saved.size }].sort((left, right) => left.name.localeCompare(right.name))
  return saved
}
async function createWatchlist(name: string, values: string[]) {
  if (!canWrite.value) throw new Error(t('ueba.readOnly'))
  try { return acceptWatchlist(await createWatchlistOnly(name, values)) }
  catch (failure) {
    if (failure instanceof ApiError && failure.status === 409 && failure.rawMessage === 'watchlist already exists') throw new Error(t('forms.duplicateName'))
    throw failure
  }
}
async function appendToWatchlist(name: string, values: string[]) {
  if (!canWrite.value) throw new Error(t('ueba.readOnly'))
  return acceptWatchlist(await appendWatchlist(name, values))
}
async function removeWatchlist(name: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('ueba.deleteWatchlistConfirm', { name }), { title: t('common.delete') })) return
  try { await deleteWatchlist(name); watchlistRevision++; watchlists.value = watchlists.value.filter(item => item.name !== name) }
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
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('ueba.title')" :description="t('ueba.description')">
      <template #actions><el-button size="small" :loading="loading" @click="loadUeba">{{ t('common.refresh') }}</el-button></template>
    </PageHeader>
    <ActionFeedback :error="loadError" />
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('ueba.readOnly') }}</div>
    <el-alert v-if="techniqueError" :title="t('ueba.techniqueDictionaryUnavailable')" :description="techniqueError" type="warning" :closable="false" show-icon style="margin-bottom:12px" />
    <el-row class="metrics-row" :gutter="12" style="margin-bottom:14px">
      <el-col :xs="24" :sm="12" :md="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.entities ?? '—' }}</div><div class="label">{{ t('ueba.entityCount') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-danger)">{{ riskSummary?.maxRisk ?? '—' }}</div><div class="label">{{ t('ueba.maxRisk') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="5"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-warning)">{{ (riskSummary?.byLevel?.CRITICAL ?? 0) + (riskSummary?.byLevel?.HIGH ?? 0) }}</div><div class="label">{{ t('ueba.highRiskEntities') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="5"><el-card shadow="never"><div class="stat-card"><div class="num">{{ riskSummary?.halfLifeHours ?? 0 }}h</div><div class="label">{{ t('ueba.halfLife') }}</div></div></el-card></el-col>
      <el-col :xs="24" :sm="12" :md="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:var(--ns-accent-fg)">{{ watchlists.length }}</div><div class="label">{{ t('ueba.watchlists') }}</div></div></el-card></el-col>
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
          :load="getWatchlist"
          :create="createWatchlist"
          :append="appendToWatchlist"
          :can-write="canWrite"
          @remove="removeWatchlist"
        />
      </el-tab-pane>
      <el-tab-pane :label="t('ueba.advancedTools')" name="score">
        <div class="workspace-hint">{{ t('ueba.scoreSimulationHint') }}</div>
        <UebaScorePanel :form="scoreForm" :result="scoreResult" :loading="scoreLoading" :error="scoreError?.message" :techniques="attackTechniques" :techniques-loading="techniquesLoading" @calculate="calcScore" />
      </el-tab-pane>
    </el-tabs>

    <UebaEntityDrawer v-model="entityDrawer" :entity="entityDetail" :loading="entityLoading" :error="entityError?.message" @retry="loadEntityDetail" @go-alarms="goToAlarms" />
  </div>
</template>
