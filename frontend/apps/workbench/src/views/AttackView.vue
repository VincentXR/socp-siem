<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { attackCoverage, listRules, listTactics, listTechniques, type Alarm, type Technique } from '../api'

const props = defineProps<{ alarms: Alarm[] }>()
type AttackCov = Awaited<ReturnType<typeof attackCoverage>>

const tactics = ref<Array<{ id: string; name: string }>>([])
const techniques = ref<Technique[]>([])
const attackTech = ref('')
const attackCov = ref<AttackCov | null>(null)
const attackLoading = ref(false)

async function loadAttack() {
  tactics.value = await listTactics() as Array<{ id: string; name: string }>
  techniques.value = await listTechniques(attackTech.value || undefined)
  await computeAttackCov()
}

async function computeAttackCov() {
  attackLoading.value = true
  try {
    const rules = await listRules() as Array<Record<string, unknown>>
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
  if (technique.count > 0) return 'background:#f56c6c;color:#fff;border-color:#f56c6c'
  if (technique.covered) return 'background:var(--ns-success);color:#fff;border-color:transparent'
  return 'background:var(--ns-bg-inset);color:var(--ns-text-3);border-color:var(--ns-border)'
}

function openUrl(url: string) { if (url) window.open(url, '_blank') }
onMounted(loadAttack)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="MITRE ATT&CK" description="查看检测规则覆盖的 ATT&CK 技术，并结合告警命中情况定位盲区。">
      <template #actions><el-button :loading="attackLoading" @click="computeAttackCov">重新计算</el-button></template>
    </PageHeader>
    <el-card shadow="never" style="margin-bottom:14px">
      <div style="display:flex;gap:20px;align-items:center;flex-wrap:wrap">
        <div><div style="font-size:12px;color:#909399">检测覆盖率</div><div style="font-size:30px;font-weight:700;color:#409eff">{{ attackCov ? attackCov.coverage : '—' }}%</div></div>
        <div><div style="font-size:12px;color:#909399">已覆盖 / 总技术</div><div style="font-size:18px;font-weight:600">{{ attackCov ? attackCov.coveredTechniques : '—' }} / {{ attackCov ? attackCov.totalTechniques : '—' }}</div></div>
        <el-select v-model="attackTech" placeholder="全部战术" clearable style="width:170px" @change="loadAttack">
          <el-option v-for="tactic in tactics" :key="tactic.id" :label="tactic.name" :value="tactic.id" />
        </el-select>
      </div>
      <div v-if="attackCov && attackCov.uncovered.length" style="margin-top:10px">
        <span style="color:#909399;font-size:12px">未覆盖技术：</span>
        <el-tag v-for="technique in attackCov.uncovered.slice(0, 24)" :key="technique" size="small" type="info" style="margin:2px">{{ technique }}</el-tag>
      </div>
    </el-card>
    <el-card shadow="never" style="margin-bottom:14px">
      <template #header>ATT&CK 战术矩阵（红=有告警命中 · 绿=已覆盖 · 灰=未覆盖）</template>
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
        <el-table-column prop="id" label="技术 ID" width="110" />
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tactic" label="战术" width="130" show-overflow-tooltip />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="primary" size="small" @click="openUrl(row.url)">详情</el-button></template></el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
