<script setup lang="ts">
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
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { attackCoverage, listRules, listTactics, listTechniques, type Alarm, type Tactic, type Technique, updateTechnique } from '../api'
import { useI18n } from '../composables/useI18n'

const props = defineProps<{ alarms: Alarm[] }>()
const { t } = useI18n()
type AttackCov = Awaited<ReturnType<typeof attackCoverage>>

const tactics = ref<Tactic[]>([])
const techniques = ref<Technique[]>([])
const attackTech = ref('')
const attackCov = ref<AttackCov | null>(null)
const attackLoading = ref(false)
const techniqueDialogVisible = ref(false)
const editingTechniqueId = ref('')
const techniqueForm = ref({ name: '', tactic: '', url: '', description: '' })

async function loadAttack() {
  tactics.value = await listTactics()
  techniques.value = await listTechniques(attackTech.value || undefined)
  await computeAttackCov()
}

async function computeAttackCov() {
  attackLoading.value = true
  try {
    const rules = await listRules()
    const techs = rules.map(rule => String(rule.mitre ?? '')).filter(Boolean)
    attackCov.value = await attackCoverage(techs)
  } catch { attackCov.value = null }
  finally { attackLoading.value = false }
}

const mitreCounts = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {}
  for (const alarm of props.alarms) if (alarm.mitre) counts[alarm.mitre] = (counts[alarm.mitre] || 0) + 1
  return counts
})
const uncoveredSet = computed(() => new Set(attackCov.value?.uncovered ?? []))
const attackMatrix = computed(() => {
  const byTactic: Record<string, Array<Technique & { covered: boolean; count: number }>> = {}
  for (const technique of techniques.value) {
    const key = technique.tactic || ''
    ;(byTactic[key] ||= []).push({ ...technique, covered: !uncoveredSet.value.has(technique.id), count: mitreCounts.value[technique.id] || 0 })
  }
  return tactics.value.map(tactic => {
    const techs = byTactic[tactic.id] || byTactic[tactic.name] || []
    return { tac: tactic, techs, total: techs.length, covered: techs.filter(technique => technique.covered).length }
  })
})

function techStyle(technique: { covered: boolean; count: number }) {
  if (technique.count > 0) return 'background:var(--ns-danger);color:var(--ns-on-danger);border-color:var(--ns-danger)'
  if (technique.covered) return 'background:var(--ns-success);color:var(--ns-on-success);border-color:transparent'
  return 'background:var(--ns-bg-inset);color:var(--ns-text-3);border-color:var(--ns-border)'
}

function openUrl(url: string) { if (url) window.open(url, '_blank') }

function openTechniqueEdit(technique: Technique) {
  editingTechniqueId.value = technique.id
  techniqueForm.value = { name: technique.name, tactic: technique.tactic, url: technique.url, description: technique.description }
  techniqueDialogVisible.value = true
}

async function saveTechnique() {
  if (!techniqueForm.value.name.trim()) {
    ElMessage.warning(t('attack.enterName'))
    return
  }
  try {
    await updateTechnique(editingTechniqueId.value, {
      name: techniqueForm.value.name.trim(), tactic: techniqueForm.value.tactic,
      url: techniqueForm.value.url.trim(), description: techniqueForm.value.description.trim(),
    })
    techniqueDialogVisible.value = false
    ElMessage.success(t('attack.updated'))
    await loadAttack()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('attack.updateFailed'))
  }
}

onMounted(loadAttack)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('attack.title')" :description="t('attack.description')">
      <template #actions><el-button :loading="attackLoading" @click="computeAttackCov">{{ t('attack.refreshCoverage') }}</el-button></template>
    </PageHeader>
    <el-card shadow="never" style="margin-bottom:14px">
      <div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
        <div><div style="font-size:12px;color:var(--ns-text-3)">{{ t('attack.detectionCoverage') }}</div><div style="font-size:30px;font-weight:700;color:var(--ns-accent-fg)">{{ attackCov ? attackCov.coverage : t('time.notAvailable') }}%</div></div>
        <div><div style="font-size:12px;color:var(--ns-text-3)">{{ t('attack.coveredTotal') }}</div><div style="font-size:18px;font-weight:600">{{ attackCov ? attackCov.coveredTechniques : t('time.notAvailable') }} / {{ attackCov ? attackCov.totalTechniques : t('time.notAvailable') }}</div></div>
        <el-select v-model="attackTech" :placeholder="t('attack.allTactics')" clearable style="width:170px" @change="loadAttack">
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
      <div class="attack-matrix">
        <div v-for="column in attackMatrix" :key="column.tac.id" class="am-col">
          <div class="am-head">{{ column.tac.name }}<span class="am-cov">{{ column.covered }}/{{ column.total }}</span></div>
          <div v-for="technique in column.techs" :key="technique.id" class="am-cell" :style="techStyle(technique)" @click="openUrl(technique.url)" :title="technique.id + ' ' + technique.name">
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
        <el-table-column :label="t('attack.operation')" width="125"><template #default="{ row }"><el-button link type="primary" size="small" @click="openUrl(row.url)">{{ t('attack.details') }}</el-button><el-button link type="primary" size="small" @click="openTechniqueEdit(row as Technique)">{{ t('attack.edit') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="techniqueDialogVisible" :title="t('attack.editTitle', { id: editingTechniqueId })" width="620px">
      <el-form label-width="80px">
        <el-form-item :label="t('attack.name')" required><el-input v-model="techniqueForm.name" /></el-form-item>
        <el-form-item :label="t('attack.tactic')"><el-select v-model="techniqueForm.tactic" style="width: 240px"><el-option v-for="tactic in tactics" :key="tactic.id" :label="tactic.name" :value="tactic.id" /></el-select></el-form-item>
        <el-form-item :label="t('attack.detailUrl')"><el-input v-model="techniqueForm.url" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="techniqueForm.description" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="techniqueDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="saveTechnique">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>
