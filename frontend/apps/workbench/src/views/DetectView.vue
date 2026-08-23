<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/radio/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElRadio, ElRadioGroup } from 'element-plus/es/components/radio/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import SevBadge from '../components/SevBadge.vue'
import {
  createGasRule, deleteGasRule, gasIngest, gasStats, listRules, SEVERITIES, updateGasRule,
  type GasStats, type RuleSpec,
} from '../api'

const rules = ref<RuleSpec[]>([])
const gasStat = ref<GasStats>({ rules: 0, eventCount: 0, alertCount: 0, dropCount: 0, suppressedCount: 0, queueLoad: 0 })
const ingestMsg = ref('')
const ingestSource = ref('auth')
const ingestResult = ref('')
const showRuleEditor = ref(false)
const ruleEditingId = ref<string | null>(null)
const ruleForm = ref({
  id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', keyField: 'src_ip', threshold: 5, window: '60s', enabled: true,
  match: [{ field: 'msg', op: 'contains', value: '' }],
  steps: [] as Array<Array<{ field: string; op: string; value: string }>>,
})
const COND_FIELDS = ['source', 'host', 'msg', 'severity', 'src_ip', 'dst_ip', 'user', 'action', 'http_method', 'url', 'bytes']
const COND_OPS = ['eq', 'ne', 'contains', 'startswith', 'endswith', 'regex', 'gt', 'gte', 'lt', 'lte', 'ge']

async function loadRules() {
  rules.value = await listRules()
  gasStat.value = await gasStats()
}
async function doIngest() {
  try {
    const result = await gasIngest({ source: ingestSource.value, msg: ingestMsg.value, fields: { src_ip: '10.0.0.9' } })
    ingestResult.value = JSON.stringify(result)
  } catch (error) { ingestResult.value = String(error) }
}
function openRuleEditor(rule?: RuleSpec) {
  if (rule) {
    ruleEditingId.value = String(rule.id)
    ruleForm.value = {
      id: String(rule.id), name: String(rule.name ?? ''), type: String(rule.type ?? 'pattern'), severity: String(rule.severity ?? 'HIGH'),
      message: String(rule.message ?? ''), keyField: String(rule.keyField ?? 'src_ip'), threshold: Number(rule.threshold ?? 5),
      window: String(rule.window ?? '60s'), enabled: Boolean(rule.enabled ?? true),
      match: (rule.match as Array<{ field: string; op: string; value: string }> | undefined)?.length ? JSON.parse(JSON.stringify(rule.match)) : [{ field: 'msg', op: 'contains', value: '' }],
      steps: rule.steps ? JSON.parse(JSON.stringify(rule.steps)) : [],
    }
  } else {
    ruleEditingId.value = null
    ruleForm.value = { id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', keyField: 'src_ip', threshold: 5, window: '60s', enabled: true, match: [{ field: 'msg', op: 'contains', value: '' }], steps: [] }
  }
  showRuleEditor.value = true
}
async function saveRule() {
  if (!ruleForm.value.name.trim()) return
  const spec: Partial<RuleSpec> = { name: ruleForm.value.name, type: ruleForm.value.type, severity: ruleForm.value.severity, message: ruleForm.value.message, enabled: ruleForm.value.enabled, window: ruleForm.value.window }
  if (ruleEditingId.value) spec.id = ruleEditingId.value
  if (ruleForm.value.type === 'threshold') {
    spec.keyField = ruleForm.value.keyField
    spec.threshold = ruleForm.value.threshold
    spec.match = ruleForm.value.match.filter(condition => condition.value !== '')
  } else if (ruleForm.value.type === 'pattern') {
    spec.match = ruleForm.value.match.filter(condition => condition.value !== '')
  } else {
    spec.keyField = ruleForm.value.keyField
    spec.steps = ruleForm.value.steps.filter(step => step.some(condition => condition.value !== ''))
  }
  try {
    if (ruleEditingId.value) await updateGasRule(ruleEditingId.value, spec)
    else await createGasRule(spec)
    showRuleEditor.value = false
    await loadRules()
  } catch (error) { ingestResult.value = `保存失败: ${error instanceof Error ? error.message : error}` }
}
async function removeRule(id: string) {
  if (!confirm('确认删除该规则？删除后立即热更新引擎。')) return
  await deleteGasRule(id)
  await loadRules()
}
async function toggleRule(rule: RuleSpec) {
  await updateGasRule(String(rule.id), { ...rule, enabled: !rule.enabled })
  await loadRules()
}
function isRuleSpec(row: unknown): row is RuleSpec {
  if (!row || typeof row !== 'object') return false
  const candidate = row as Partial<RuleSpec>
  return typeof candidate.id === 'string' && typeof candidate.name === 'string'
    && typeof candidate.type === 'string' && typeof candidate.severity === 'string'
    && typeof candidate.enabled === 'boolean'
}
function openRuleEditorRow(row: unknown) { if (isRuleSpec(row)) openRuleEditor(row) }
function toggleRuleRow(row: unknown) { if (isRuleSpec(row)) void toggleRule(row) }
function removeRuleRow(row: unknown) { if (isRuleSpec(row)) void removeRule(row.id) }

onMounted(loadRules)
</script>

<template>
  <div class="page-pad view-enter">
    <el-row :gutter="12" style="margin-bottom:14px">
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ gasStat.rules ?? 0 }}</div><div class="label">规则数</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ gasStat.eventCount ?? 0 }}</div><div class="label">事件数</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#f56c6c">{{ gasStat.alertCount ?? 0 }}</div><div class="label">告警数</div></div></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><div class="stat-card"><div class="num">{{ (gasStat.queueLoad * 100).toFixed(0) }}%</div><div class="label">队列水位</div></div></el-card></el-col>
    </el-row>
    <el-card shadow="never" style="margin-bottom:14px"><template #header>模拟事件投递</template><div style="display:flex;gap:10px;align-items:center"><el-select v-model="ingestSource" style="width:120px"><el-option label="auth" value="auth" /><el-option label="web" value="web" /><el-option label="firewall" value="firewall" /></el-select><el-input v-model="ingestMsg" placeholder="如：Failed password for admin" style="width:360px" /><el-button type="primary" @click="doIngest">投递</el-button><span v-if="ingestResult" class="mono" style="font-size:12px;color:#67c23a">{{ ingestResult }}</span></div></el-card>
    <el-card shadow="never"><template #header><div style="display:flex;justify-content:space-between;align-items:center"><span>规则列表（可新建/编辑/删除/启停，保存后引擎热更新）</span><el-button type="primary" size="small" @click="openRuleEditor()">新建规则</el-button></div></template>
      <el-table :data="rules" size="small" border><el-table-column prop="id" label="ID" width="150" show-overflow-tooltip /><el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip /><el-table-column prop="type" label="类型" width="90" /><el-table-column prop="severity" label="级别" width="85"><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column><el-table-column label="匹配条件" min-width="220" show-overflow-tooltip><template #default="{ row }">{{ (row.match || []).map((condition: { field: string; op: string; value: string }) => `${condition.field} ${condition.op} ${condition.value}`).join(' AND ') || (row.steps || []).length + ' 步关联' || '-' }}</template></el-table-column><el-table-column label="启用" width="70"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column><el-table-column label="操作" width="170"><template #default="{ row }"><el-button link type="primary" size="small" @click="openRuleEditorRow(row)">编辑</el-button><el-button link size="small" @click="toggleRuleRow(row)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" size="small" @click="removeRuleRow(row)">删除</el-button></template></el-table-column></el-table>
    </el-card>
    <el-dialog v-model="showRuleEditor" :title="ruleEditingId ? '编辑规则' : '新建规则'" width="640px"><el-form label-width="90px"><el-form-item label="名称"><el-input v-model="ruleForm.name" placeholder="如：SSH 暴力破解" /></el-form-item><el-form-item label="类型"><el-radio-group v-model="ruleForm.type"><el-radio value="pattern">模式</el-radio><el-radio value="threshold">阈值</el-radio><el-radio value="correlation">关联</el-radio></el-radio-group></el-form-item><el-form-item label="级别"><el-select v-model="ruleForm.severity" style="width:160px"><el-option v-for="severity in SEVERITIES" :key="severity" :label="severity" :value="severity" /></el-select></el-form-item><el-form-item label="告警消息"><el-input v-model="ruleForm.message" placeholder="支持 {key} {count} {host} 占位" /></el-form-item><el-form-item label="窗口"><el-input v-model="ruleForm.window" placeholder="如 60s / 5m / 1h" style="width:120px" /></el-form-item><template v-if="ruleForm.type === 'threshold'"><el-form-item label="分组字段"><el-select v-model="ruleForm.keyField" style="width:160px"><el-option v-for="field in COND_FIELDS" :key="field" :label="field" :value="field" /></el-select></el-form-item><el-form-item label="触发阈值"><el-input v-model.number="ruleForm.threshold" type="number" style="width:120px" /></el-form-item></template><template v-if="ruleForm.type === 'correlation'"><el-form-item label="关联字段"><el-select v-model="ruleForm.keyField" style="width:160px"><el-option v-for="field in COND_FIELDS" :key="field" :label="field" :value="field" /></el-select></el-form-item><el-form-item label="关联步骤"><div v-for="(step, stepIndex) in ruleForm.steps" :key="stepIndex" style="border:1px solid #e4e7ed;border-radius:6px;padding:8px;margin-bottom:8px"><div style="font-size:12px;color:#909399;margin-bottom:4px">步骤 {{ stepIndex + 1 }}（同一实体按序命中）</div><div v-for="(condition, conditionIndex) in step" :key="conditionIndex" style="display:flex;gap:6px;margin-bottom:4px"><el-select v-model="condition.field" size="small" style="width:110px"><el-option v-for="field in COND_FIELDS" :key="field" :label="field" :value="field" /></el-select><el-select v-model="condition.op" size="small" style="width:100px"><el-option v-for="op in COND_OPS" :key="op" :label="op" :value="op" /></el-select><el-input v-model="condition.value" size="small" placeholder="值" style="flex:1" /><el-button size="small" type="danger" link @click="step.splice(conditionIndex, 1)">删</el-button></div><el-button size="small" link type="primary" @click="ruleForm.steps[stepIndex].push({ field: 'msg', op: 'contains', value: '' })">+ 条件</el-button><el-button v-if="ruleForm.steps.length > 1" size="small" link type="danger" @click="ruleForm.steps.splice(stepIndex, 1)">删除步骤</el-button></div><el-button size="small" type="primary" plain @click="ruleForm.steps.push([{ field: 'msg', op: 'contains', value: '' }])">+ 步骤</el-button></el-form-item></template><el-form-item v-else label="匹配条件"><div v-for="(condition, conditionIndex) in ruleForm.match" :key="conditionIndex" style="display:flex;gap:6px;margin-bottom:4px;width:100%"><el-select v-model="condition.field" size="small" style="width:110px"><el-option v-for="field in COND_FIELDS" :key="field" :label="field" :value="field" /></el-select><el-select v-model="condition.op" size="small" style="width:110px"><el-option v-for="op in COND_OPS" :key="op" :label="op" :value="op" /></el-select><el-input v-model="condition.value" size="small" placeholder="值（条件间为 AND）" style="flex:1" /><el-button size="small" type="danger" link @click="ruleForm.match.splice(conditionIndex, 1)">删</el-button></div><el-button size="small" type="primary" plain @click="ruleForm.match.push({ field: 'msg', op: 'contains', value: '' })">+ 条件</el-button></el-form-item><el-form-item label="启用"><el-switch v-model="ruleForm.enabled" /></el-form-item></el-form><template #footer><el-button @click="showRuleEditor = false">取消</el-button><el-button type="primary" @click="saveRule">保存并热更新</el-button></template></el-dialog>
  </div>
</template>
