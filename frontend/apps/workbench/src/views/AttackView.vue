<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import { useRequest } from '../composables/useRequest'
import { useFormDialog } from '../composables/useFormDialog'
import ActionFeedback from '../components/ActionFeedback.vue'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { attackCoverage, activeRuleTechniques, listTactics, listTechniques, alarmTechniqueCounts, type Tactic, type Technique, getTechniqueNote, saveTechniqueNote } from '../api'
import { useI18n } from '../composables/useI18n'

const { t, locale } = useI18n()
type AttackCov = Awaited<ReturnType<typeof attackCoverage>>

const tacticsRequest = useRequest<Tactic[]>()
const techniquesRequest = useRequest<Technique[]>()
const coverageRequest = useRequest<{ result: AttackCov; activeTechniques: string[] }>()
const tactics = computed(() => tacticsRequest.data.value ?? [])
const techniques = computed(() => techniquesRequest.data.value ?? [])
const loadError = computed(() => tacticsRequest.error.value?.message || techniquesRequest.error.value?.message || '')
const coverageError = computed(() => coverageRequest.error.value?.message || '')
const attackCov = computed(() => coverageRequest.data.value?.result ?? null)
const activeTechniqueIds = computed(() => new Set(coverageRequest.data.value?.activeTechniques ?? []))
const activityRequest = useRequest<{ counts: Record<string, number>; until: string }>()
const activityLoading = activityRequest.loading
const activityError = computed(() => activityRequest.error.value?.message || '')
const activityTime = computed(() => {
  const instant = activityRequest.data.value?.until
  return instant ? new Date(instant).toLocaleString(locale.value) : t('time.notAvailable')
})
const attackLoading = coverageRequest.loading
const catalogLoading = techniquesRequest.loading
const attackTech = ref('')
const noteRequest = useRequest<{ note: string }>()
const noteLoaded = ref(false)
const noteSaving = ref(false)
const techniqueDialogVisible = ref(false)
const noteText = ref('')
const noteLoading = computed(() => noteRequest.loading.value || noteSaving.value)
const noteSaveError = ref('')
const noteError = computed(() => noteRequest.error.value?.message || noteSaveError.value)
const editingTechniqueId = ref('')
const techniqueForm = ref({ name: '', tactic: '', url: '', description: '' })
let disposed = false

async function loadTechniques() {
  if (disposed) return
  const tactic = attackTech.value || undefined
  const result = await techniquesRequest.execute(async signal => (await listTechniques(tactic, { signal })).items)
  if (result) await loadActivity()
}
async function loadAttack() {
  if (disposed) return
  await Promise.all([
    tacticsRequest.execute(async signal => (await listTactics({ signal })).items),
    loadTechniques(),
  ])
}
async function computeAttackCov() {
  if (disposed) return
  await coverageRequest.execute(async signal => {
    const techs = await activeRuleTechniques({ signal })
    signal.throwIfAborted()
    const result = await attackCoverage(techs, { signal })
    return { result, activeTechniques: techs }
  })
}
watch(attackTech, () => { techniquesRequest.reset(); activityRequest.reset(); void loadTechniques() }, { flush: 'sync' })

async function loadActivity() {
  if (disposed || !techniques.value.length) return
  const ids = [...new Set(techniques.value.map(technique => technique.id))]
  await activityRequest.execute(async signal => {
    const counts: Record<string, number> = Object.create(null)
    let until = ''
    for (let index = 0; index < ids.length; index += 100) {
      const result = await alarmTechniqueCounts(ids.slice(index, index + 100), { signal })
      signal.throwIfAborted()
      Object.assign(counts, result.counts)
      if (!until || Date.parse(result.until) < Date.parse(until)) until = result.until
    }
    return { counts, until }
  })
}
const mitreCounts = computed(() => activityRequest.data.value?.counts ?? Object.create(null) as Record<string, number>)
const attackMatrix = computed(() => {
  const byTactic: Record<string, Array<Technique & { covered: boolean; count: number }>> = {}
  for (const technique of techniques.value) {
    const key = technique.tactic || ''
    ;(byTactic[key] ||= []).push({ ...technique, covered: Boolean(attackCov.value) && activeTechniqueIds.value.has(technique.id), count: mitreCounts.value[technique.id] || 0 })
  }
  return tactics.value.filter(tactic => !attackTech.value || tactic.id === attackTech.value).map(tactic => {
    const techs = byTactic[tactic.id] || byTactic[tactic.name] || []
    return { tac: tactic, techs, total: techs.length, covered: techs.filter(technique => technique.covered).length }
  })
})

function techStyle(technique: { covered: boolean; count: number }) {
  if (technique.count > 0) return 'background:var(--ns-danger);color:var(--ns-on-danger);border-color:var(--ns-danger)'
  if (technique.covered) return 'background:var(--ns-success);color:var(--ns-on-success);border-color:transparent'
  return 'background:var(--ns-bg-inset);color:var(--ns-text-3);border-color:var(--ns-border)'
}

function openUrl(url: string): void {
  let target: URL | null = null
  try { target = url ? new URL(url, window.location.origin) : null } catch { target = null }
  if (!target || target.protocol !== 'https:') {
    ElMessage.warning(t('attack.linkBlocked'))
    return
  }
  const opened = window.open(target.href, '_blank', 'noopener,noreferrer')
  if (opened) opened.opener = null
}

function cellAria(technique: { id: string; name: string; count: number }): string {
  return technique.count > 0
    ? t('attack.cellAriaHits', { id: technique.id, name: technique.name, count: technique.count })
    : `${technique.id} ${technique.name}`
}

async function loadTechniqueNote() {
  if (disposed || !techniqueDialogVisible.value || noteSaving.value) return
  const id = editingTechniqueId.value
  const result = await noteRequest.execute(signal => getTechniqueNote(id, { signal }))
  if (disposed || !techniqueDialogVisible.value || editingTechniqueId.value !== id || !result) return
  noteText.value = result.note
  noteLoaded.value = true
  noteGuard.markSaved()
}
async function openTechniqueEdit(technique: Technique) {
  if (disposed || noteSaving.value) return
  if (techniqueDialogVisible.value && !await noteGuard.canLeave()) return
  if (disposed) return
  noteRequest.reset()
  editingTechniqueId.value = technique.id
  techniqueForm.value = { name: technique.name, tactic: technique.tactic, url: technique.url, description: technique.description }
  noteSaveError.value = ''
  noteText.value = ''
  noteLoaded.value = false
  techniqueDialogVisible.value = true
  noteGuard.markSaved()
  await loadTechniqueNote()
}
async function saveTechnique() {
  if (disposed || !canWrite.value || noteLoading.value || !noteLoaded.value) return
  const id = editingTechniqueId.value, note = noteText.value
  noteSaving.value = true
  noteSaveError.value = ''
  try {
    await saveTechniqueNote(id, note)
    if (disposed) return
    noteGuard.markSaved()
    techniqueDialogVisible.value = false
    ElMessage.success(t('attack.updated'))
  } catch (error) {
    if (!disposed) noteSaveError.value = error instanceof Error ? error.message : t('attack.updateFailed')
  } finally { if (!disposed) noteSaving.value = false }
}
const noteGuard = useFormDialog(techniqueDialogVisible, () => noteText.value, () => noteSaving.value)
watch(techniqueDialogVisible, visible => {
  if (!visible) { noteRequest.reset(); noteLoaded.value = false }
}, { flush: 'sync' })
onMounted(() => { void loadAttack(); void computeAttackCov() })
onUnmounted(() => {
  disposed = true
  tacticsRequest.cancel(); techniquesRequest.cancel(); coverageRequest.cancel(); activityRequest.cancel(); noteRequest.cancel()
})
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="loadError" />
    <el-button v-if="loadError" link @click="loadAttack">{{ t('common.retry') }}</el-button>
    <ActionFeedback :error="coverageError ? `${attackCov ? t('attack.staleCoverage') : t('attack.coverageUnavailable')} ${coverageError}` : ''" />
    <PageHeader :title="t('attack.title')" :description="t('attack.description')">
      <template #actions><el-button :loading="activityLoading" :disabled="!techniques.length" @click="loadActivity">{{ t('attack.refreshActivity') }}</el-button><el-button :loading="attackLoading" @click="computeAttackCov">{{ t('attack.refreshCoverage') }}</el-button></template>
    </PageHeader>
    <el-card shadow="never" style="margin-bottom:14px">
      <div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
        <div><div style="font-size:12px;color:var(--ns-text-3)">{{ t('attack.detectionCoverage') }}</div><div style="font-size:30px;font-weight:700;color:var(--ns-accent-fg)">{{ attackCov ? attackCov.coverage : t('time.notAvailable') }}<span v-if="attackCov">%</span></div></div>
        <div><div style="font-size:12px;color:var(--ns-text-3)">{{ t('attack.coveredTotal') }}</div><div style="font-size:18px;font-weight:600">{{ attackCov ? attackCov.coveredTechniques : t('time.notAvailable') }} / {{ attackCov ? attackCov.totalTechniques : t('time.notAvailable') }}</div></div>
        <el-select v-model="attackTech" :placeholder="t('attack.allTactics')" clearable style="width:170px">
          <el-option v-for="tactic in tactics" :key="tactic.id" :label="tactic.name" :value="tactic.id" />
        </el-select>
      </div>
      <div v-if="attackCov && attackCov.uncovered.length" style="margin-top:10px">
        <span style="color:var(--ns-text-3);font-size:12px">{{ t('attack.uncoveredLabel') }}</span>
        <el-tag v-for="technique in attackCov.uncovered.slice(0, 24)" :key="technique" size="small" type="info" style="margin:2px">{{ technique }}</el-tag>
      </div>
    </el-card>
    <el-card shadow="never" style="margin-bottom:14px">
      <template #header>{{ t('attack.matrixTitle') }}</template>
      <p v-if="catalogLoading" role="status">{{ t('common.loading') }}</p>
      <p v-if="techniques.length" class="attack-activity-scope">{{ t('attack.activityScope') }} {{ t('attack.activityCalculated', { value: activityTime }) }}</p>
      <ActionFeedback :error="activityError ? `${activityRequest.data.value ? t('attack.staleActivity') : t('attack.activityUnavailable')} ${activityError}` : ''" />
      <div class="attack-matrix">
        <div v-for="column in attackMatrix" :key="column.tac.id" class="am-col">
          <div class="am-head">{{ column.tac.name }}<span class="am-cov">{{ attackCov ? column.covered : t('time.notAvailable') }}/{{ column.total }}</span></div>
          <div v-for="technique in column.techs" :key="technique.id" class="am-cell" :style="techStyle(technique)" role="button" :tabindex="technique.url ? 0 : -1" :aria-disabled="technique.url ? undefined : 'true'" @click="openUrl(technique.url)" @keydown.enter.space.prevent="openUrl(technique.url)" :title="technique.id + ' ' + technique.name" :aria-label="cellAria(technique)">
            <span class="am-id">{{ technique.id }}</span><span v-if="technique.count" class="am-badge">{{ technique.count }}</span>
          </div>
        </div>
      </div>
    </el-card>
    <el-card shadow="never">
      <el-table :data="techniques" size="small" border>
        <el-table-column prop="id" :label="t('attack.techniqueId')" width="110" />
        <el-table-column prop="name" :label="t('attack.name')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tactic" :label="t('attack.tactic')" width="130" show-overflow-tooltip />
        <el-table-column :label="t('attack.operation')" width="125"><template #default="{ row }"><el-button link type="primary" size="small" @click="openUrl(row.url)">{{ t('attack.details') }}</el-button><el-button link type="primary" size="small" @click="openTechniqueEdit(row as Technique)">{{ t('forms.note') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="techniqueDialogVisible" :before-close="noteGuard.beforeClose" :title="t('attack.editTitle', { id: editingTechniqueId })" width="640px">
      <p>{{ t('forms.standardReadOnly') }}</p><p v-if="noteError" role="alert">{{ noteError }}</p><el-button v-if="!noteLoaded && noteError" link :loading="noteLoading" @click="loadTechniqueNote">{{ t('common.retry') }}</el-button><el-form label-position="top">
        <el-form-item :label="t('attack.name')" required><el-input disabled v-model="techniqueForm.name" /></el-form-item>
        <el-form-item :label="t('attack.tactic')"><el-select disabled v-model="techniqueForm.tactic" style="width: 240px"><el-option v-for="tactic in tactics" :key="tactic.id" :label="tactic.name" :value="tactic.id" /></el-select></el-form-item>
        <el-form-item :label="t('attack.detailUrl')"><el-input disabled v-model="techniqueForm.url" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input disabled v-model="techniqueForm.description" type="textarea" :rows="4" /></el-form-item>
        <el-form-item :label="t('forms.note')"><el-input :readonly="!canWrite" v-model="noteText" type="textarea" :rows="5" maxlength="4000" :disabled="noteLoading || !noteLoaded" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="noteGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="noteLoading" :disabled="!noteLoaded" @click="saveTechnique">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>
