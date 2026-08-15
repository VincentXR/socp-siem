<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/col/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/row/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCol from 'element-plus/es/components/col/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElRow from 'element-plus/es/components/row/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import {
  createOutput, createParseRule, createSource, deleteOutput, deleteParseRule, deleteSource,
  ingestSummary, listCategories, listIngestTasks, listOutputs, listParseRules, listSources,
  renderConfig, startIngestTask, stopIngestTask, testIngestTask, previewParse,
  SOURCE_TYPES, PARSE_FORMATS,
  type IngestTask, type IngestSummary, type LogCategory, type LogSource, type ParseRule, type SinkTarget,
} from '../api'

const ingestTab = ref('tasks')
const sources = ref<LogSource[]>([])
const outputs = ref<SinkTarget[]>([])
const parseRules = ref<ParseRule[]>([])
const logCategories = ref<LogCategory[]>([])
const newSource = ref({ name: '', type: 'FILE', format: 'AUTO', path: '', address: '', topic: '', env: 'local', readFrom: 'beginning', multiline: '', protocol: 'tcp', charset: 'utf-8', timezone: 'Asia/Shanghai', tags: '', frequency: 1, categoryId: '', groupId: '', enabled: true })
const newOutput = ref({ name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true })
const newRule = ref({ name: '', format: 'REGEX', pattern: '', sourceId: '', enabled: true, order: 10 })
const previewLine = ref('Aug 07 01:00:00 web01 sshd[123]: Failed password for admin from 10.0.0.99 port 55006 ssh2')
const previewRuleId = ref('')
const previewResult = ref<{ matched: boolean; fields: Record<string, string>; error?: string } | null>(null)
const renderText = ref('')
const showRender = ref(false)
const showSourceDialog = ref(false)
const showOutputDialog = ref(false)
const showRuleDialog = ref(false)

const tasks = ref<IngestTask[]>([])
const taskSummary = ref<IngestSummary | null>(null)
const taskBusy = ref<Record<string, boolean>>({})
const testDialog = ref(false)
const testTarget = ref<IngestTask | null>(null)
const testSample = ref('')
const testResult = ref<Record<string, unknown> | null>(null)
const testLoading = ref(false)

type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'
const HEALTH_META: Record<string, { text: string; type: TagType }> = {
  HEALTHY: { text: '正常', type: 'success' }, DEGRADED: { text: '降级', type: 'warning' },
  STALE: { text: '静默', type: 'warning' }, IDLE: { text: '待接入', type: 'info' },
  ERROR: { text: '异常', type: 'danger' }, DISABLED: { text: '已停用', type: 'info' },
}
function healthMeta(health: string) { return HEALTH_META[health] ?? { text: health, type: 'info' } }
function fmtBytes(n: number) {
  if (!n) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']; let i = 0; let value = n
  while (value >= 1024 && i < units.length - 1) { value /= 1024; i++ }
  return `${Math.round(value * 10) / 10} ${units[i]}`
}
function fmtTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }

async function loadSources() { sources.value = await listSources() }
async function loadOutputs() { outputs.value = await listOutputs() }
async function loadParseRules() { parseRules.value = await listParseRules() }
async function loadTasks() {
  const [taskResult, summaryResult] = await Promise.allSettled([listIngestTasks(), ingestSummary()])
  tasks.value = taskResult.status === 'fulfilled' ? taskResult.value : []
  taskSummary.value = summaryResult.status === 'fulfilled' ? summaryResult.value : null
}
function onIngestTab(key: string | number) {
  const tab = String(key)
  ingestTab.value = tab
  if (tab === 'sources') loadSources()
  if (tab === 'outputs') loadOutputs()
  if (tab === 'rules') loadParseRules()
  if (tab === 'tasks') loadTasks()
}

async function addSource() {
  const source: Record<string, unknown> = {
    name: newSource.value.name, type: newSource.value.type, format: newSource.value.format,
    env: newSource.value.env, enabled: newSource.value.enabled, readFrom: newSource.value.readFrom,
    protocol: newSource.value.protocol, charset: newSource.value.charset, timezone: newSource.value.timezone,
    frequency: Number(newSource.value.frequency) || 1, groupId: newSource.value.groupId || null,
    categoryId: newSource.value.categoryId || null,
  }
  if (newSource.value.multiline.trim()) source.multiline = newSource.value.multiline.trim()
  if (newSource.value.tags.trim()) source.tags = newSource.value.tags.split(/[,，\s]+/).filter(Boolean)
  if (newSource.value.type === 'FILE') source.path = newSource.value.path || 'demo/sample.log'
  if (newSource.value.type === 'SOCKET' || newSource.value.type === 'SYSLOG') source.address = newSource.value.address || '0.0.0.0:5514'
  if (newSource.value.type === 'KAFKA') source.topic = newSource.value.topic || 'socp-raw'
  await createSource(source)
  newSource.value.name = ''
  showSourceDialog.value = false
  await loadSources()
}
async function removeSource(id: string) { await deleteSource(id); await loadSources() }
async function doRender() { renderText.value = await renderConfig(); showRender.value = true }
function copyRender() { navigator.clipboard.writeText(renderText.value) }
async function addOutput() {
  await createOutput({ name: newOutput.value.name, type: newOutput.value.type, uri: newOutput.value.uri, authToken: newOutput.value.authToken || null, enabled: newOutput.value.enabled })
  newOutput.value = { name: '', type: 'GLS_INGEST', uri: '', authToken: '', enabled: true }
  showOutputDialog.value = false
  await loadOutputs()
}
async function removeOutput(id: string) { await deleteOutput(id); await loadOutputs() }
async function addParseRule() {
  await createParseRule({
    name: newRule.value.name, format: newRule.value.format,
    pattern: newRule.value.format === 'REGEX' ? newRule.value.pattern : null,
    sourceId: newRule.value.sourceId || null, enabled: newRule.value.enabled, order: newRule.value.order,
    mapping: [], setFields: [],
  } as Partial<ParseRule>)
  showRuleDialog.value = false
  newRule.value = { name: '', format: 'REGEX', pattern: '', sourceId: '', enabled: true, order: 10 }
  await loadParseRules()
}
async function removeParseRule(id: string) { await deleteParseRule(id); await loadParseRules() }
async function doPreview() {
  try { previewResult.value = await previewParse({ ruleId: previewRuleId.value || undefined, line: previewLine.value }) }
  catch (error) { previewResult.value = { matched: false, fields: {}, error: error instanceof Error ? error.message : String(error) } }
}

async function toggleTask(task: IngestTask) {
  taskBusy.value = { ...taskBusy.value, [task.id]: true }
  try { task.enabled ? await stopIngestTask(task.id) : await startIngestTask(task.id); await loadTasks() }
  finally { taskBusy.value = { ...taskBusy.value, [task.id]: false } }
}
function openTest(task: IngestTask) { testTarget.value = task; testSample.value = ''; testResult.value = null; testDialog.value = true }
function toggleTaskRow(row: unknown) { toggleTask(row as IngestTask) }
function openTestRow(row: unknown) { openTest(row as IngestTask) }
async function runTest() {
  if (!testTarget.value) return
  testLoading.value = true
  try { testResult.value = await testIngestTask(testTarget.value.id, testSample.value.trim() || undefined) as unknown as Record<string, unknown>; await loadTasks() }
  catch (error) { testResult.value = { ok: false, error: String(error) } }
  finally { testLoading.value = false }
}

onMounted(async () => {
  await Promise.allSettled([loadSources(), loadOutputs(), loadParseRules(), loadTasks(), listCategories().then(result => { logCategories.value = result })])
})
</script>

<template>
  <div class="page-pad view-enter">
    <el-tabs v-model="ingestTab" @tab-change="onIngestTab">
      <el-tab-pane label="接入任务" name="tasks">
        <el-row :gutter="12" style="margin-bottom:14px">
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.enabledSources ?? 0 }}/{{ taskSummary?.sources ?? 0 }}</div><div class="label">运行中 / 总任务</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#409eff">{{ taskSummary?.eps1m ?? 0 }}</div><div class="label">总 EPS(1m)</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" style="color:#67c23a">{{ taskSummary?.accepted ?? 0 }}</div><div class="label">已接收</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ taskSummary?.forwarded ?? 0 }}</div><div class="label">已转发检测</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num" :style="{ color: (taskSummary?.skipped ?? 0) > 0 ? '#e6a23c' : '#909399' }">{{ taskSummary?.skipped ?? 0 }}</div><div class="label">解析跳过</div></div></el-card></el-col>
          <el-col :span="4"><el-card shadow="never"><div class="stat-card"><div class="num">{{ fmtBytes(taskSummary?.bytes ?? 0) }}</div><div class="label">累计流量</div></div></el-card></el-col>
        </el-row>
        <el-card shadow="never">
          <template #header><div style="display:flex;align-items:center;gap:10px"><span>接入任务（配置 + 运行指标）</span><el-tag v-for="(count, health) in (taskSummary?.byHealth ?? {})" :key="health" size="small" :type="healthMeta(String(health)).type" style="margin-left:2px">{{ healthMeta(String(health)).text }} {{ count }}</el-tag><el-button size="small" style="margin-left:auto" @click="loadTasks">刷新</el-button></div></template>
          <el-table :data="tasks" size="small">
            <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag :type="healthMeta(row.runtime.health).type" size="small" effect="dark">{{ healthMeta(row.runtime.health).text }}</el-tag></template></el-table-column>
            <el-table-column label="任务" min-width="150"><template #default="{ row }"><div style="font-weight:600">{{ row.name }}</div><div class="mono" style="font-size:11px;color:#909399">{{ row.collector }}</div></template></el-table-column>
            <el-table-column prop="type" label="接入方式" width="110" />
            <el-table-column prop="format" label="解析格式" width="90" />
            <el-table-column label="采集目标" min-width="180" show-overflow-tooltip><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.target }}</span></template></el-table-column>
            <el-table-column label="EPS(1m/5m)" width="110"><template #default="{ row }"><span :style="{ color: row.runtime.eps1m > 0 ? '#67c23a' : '#c0c4cc', fontWeight: 600 }">{{ row.runtime.eps1m }}</span><span style="color:#c0c4cc"> / {{ row.runtime.eps5m }}</span></template></el-table-column>
            <el-table-column label="接收 / 转发 / 跳过" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ row.runtime.accepted }} / {{ row.runtime.forwarded }} / <span :style="{ color: row.runtime.skipped > 0 ? '#e6a23c' : 'inherit' }">{{ row.runtime.skipped }}</span></span></template></el-table-column>
            <el-table-column label="最近数据" width="150"><template #default="{ row }"><span class="mono" style="font-size:12px">{{ fmtTime(row.runtime.lastAt) }}</span></template></el-table-column>
            <el-table-column label="操作" width="170"><template #default="{ row }"><el-button link :type="row.enabled ? 'warning' : 'success'" size="small" :loading="taskBusy[row.id]" @click="toggleTaskRow(row)">{{ row.enabled ? '停止' : '启动' }}</el-button><el-button link type="primary" size="small" @click="openTestRow(row)">连通性自测</el-button></template></el-table-column>
            <el-table-column type="expand"><template #default="{ row }"><div style="padding:8px 20px;font-size:12px;color:#606266"><div>环境：{{ row.env || '—' }} · 类别：{{ row.categoryId || '—' }} · 输出：{{ row.sinkTargetId || '默认' }} · 创建：{{ fmtTime(row.createdAt) }}</div><div style="margin-top:4px">绑定解析规则：<el-tag v-for="p in row.parseRuleIds" :key="p" size="small" style="margin-right:4px">{{ p }}</el-tag><span v-if="!row.parseRuleIds?.length" style="color:#c0c4cc">自动识别</span></div><div v-if="row.runtime.lastError" style="margin-top:4px;color:#f56c6c">最近错误（{{ fmtTime(row.runtime.lastErrorAt ?? null) }}）：{{ row.runtime.lastError }}</div></div></template></el-table-column>
          </el-table>
        </el-card>
        <el-dialog v-model="testDialog" :title="`连通性自测 · ${testTarget?.name ?? ''}`" width="680px">
          <div style="font-size:12px;color:#909399;margin-bottom:8px">留空则按该源类型自动生成样例日志；样例会真实走完 解析 → 富化 → 转发检测 全链路。</div>
          <el-input v-model="testSample" type="textarea" :rows="4" placeholder="留空使用默认样例，或粘贴一行原始日志 / 一条 JSON" />
          <div v-if="testResult" style="margin-top:12px"><el-alert :type="testResult.ok ? 'success' : 'error'" :closable="false" :title="testResult.ok ? '管线贯通：样例已被接收并转发' : '未通过：样例未被接收，检查解析规则或输出配置'" /><pre class="mono test-out">{{ JSON.stringify(testResult, null, 2) }}</pre></div>
          <template #footer><el-button @click="testDialog = false">关闭</el-button><el-button type="primary" :loading="testLoading" @click="runTest">执行自测</el-button></template>
        </el-dialog>
      </el-tab-pane>

      <el-tab-pane label="输入源" name="sources">
        <div class="add-bar"><el-button type="primary" @click="showSourceDialog = true">+ 新增日志源</el-button><el-button @click="loadSources">刷新</el-button><el-button type="primary" plain @click="doRender">渲染 vector.toml</el-button><span class="hint">接入方式 + 完整参数；保存后渲染 vector.toml</span></div>
        <el-dialog v-model="showSourceDialog" title="新增日志源" width="640px">
          <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px"><el-input v-model="newSource.name" placeholder="名称，如 fw-syslog" /><el-select v-model="newSource.type" placeholder="接入方式"><el-option v-for="type in SOURCE_TYPES" :key="type" :label="type" :value="type" /></el-select><el-select v-model="newSource.format" placeholder="解析格式"><el-option v-for="format in PARSE_FORMATS" :key="format" :label="format" :value="format" /></el-select><el-select v-model="newSource.categoryId" placeholder="日志类别" clearable><el-option v-for="category in logCategories" :key="category.id" :label="`${category.code} ${category.name}`" :value="category.id" /></el-select><el-input v-model="newSource.env" placeholder="环境标签" /></div>
          <div v-if="newSource.type === 'FILE'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.path" placeholder="文件路径/glob，如 /var/log/auth.log" /><el-select v-model="newSource.readFrom"><el-option label="beginning 全量回放" value="beginning" /><el-option label="end 只收新增" value="end" /></el-select><el-input v-model.number="newSource.frequency" placeholder="轮询间隔(秒)" /></div>
          <div v-else-if="newSource.type === 'SOCKET' || newSource.type === 'SYSLOG'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.address" placeholder="监听 host:port，如 0.0.0.0:514" /><el-select v-model="newSource.protocol"><el-option label="UDP" value="udp" /><el-option label="TCP" value="tcp" /><el-option label="TLS" value="tls" /></el-select></div>
          <div v-else-if="newSource.type === 'KAFKA'" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-input v-model="newSource.topic" placeholder="主题，如 socp-raw" /><el-input v-model="newSource.groupId" placeholder="消费组，如 search-group" /></div>
          <div v-else-if="['WINDOWS_EVENT', 'AGENT', 'HTTP_API', 'DATABASE', 'CLOUD'].includes(newSource.type)" style="margin-top:10px"><el-alert type="info" :closable="false" :title="`${newSource.type} 由对应采集器负责（Winlogbeat/Agent/Webhook/DB CDC/云 SDK），采集器输出统一走 NDJSON → SEARCH ingest`" /></div>
          <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px;margin-top:10px"><el-select v-model="newSource.charset" placeholder="字符集"><el-option label="UTF-8" value="utf-8" /><el-option label="GBK" value="gbk" /><el-option label="ISO-8859-1" value="iso-8859-1" /></el-select><el-select v-model="newSource.timezone" placeholder="时区"><el-option label="Asia/Shanghai" value="Asia/Shanghai" /><el-option label="UTC" value="UTC" /><el-option label="Asia/Tokyo" value="Asia/Tokyo" /></el-select><el-input v-model="newSource.tags" placeholder="标签（逗号分隔），如 app=nginx,team=infra" /></div>
          <template #footer><el-switch v-model="newSource.enabled" active-text="启用" style="margin-right:12px" /><el-button @click="showSourceDialog = false">取消</el-button><el-button type="success" @click="addSource">新增日志源</el-button></template>
        </el-dialog>
        <el-card shadow="never"><el-table :data="sources" size="small"><el-table-column prop="name" label="名称" width="130" /><el-table-column prop="type" label="类型" width="110" /><el-table-column prop="format" label="格式" width="80" /><el-table-column label="目标" min-width="160"><template #default="{ row }">{{ row.path || row.address || row.topic || '-' }}</template></el-table-column><el-table-column label="协议" width="70"><template #default="{ row }">{{ row.protocol || '-' }}</template></el-table-column><el-table-column prop="env" label="环境" width="65" /><el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column><el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeSource(row.id)">删除</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="输出配置" name="outputs">
        <div class="add-bar"><el-button type="primary" @click="showOutputDialog = true">+ 新增输出</el-button><span class="hint">渲染时取第一个启用的输出作为 Vector sink 目标；缺省为 SEARCH 自身 ingest</span></div>
        <el-dialog v-model="showOutputDialog" title="新增输出" width="560px"><el-form label-width="80px"><el-form-item label="名称"><el-input v-model="newOutput.name" placeholder="名称" /></el-form-item><el-form-item label="类型"><el-select v-model="newOutput.type" style="width:200px"><el-option label="GLS_INGEST" value="GLS_INGEST" /><el-option label="OPENSEARCH" value="OPENSEARCH" /><el-option label="HTTP" value="HTTP" /></el-select></el-form-item><el-form-item label="目标 URL"><el-input v-model="newOutput.uri" placeholder="如 http://host:9200/_bulk" /></el-form-item><el-form-item label="启用"><el-switch v-model="newOutput.enabled" active-text="启用" /></el-form-item></el-form><template #footer><el-button @click="showOutputDialog = false">取消</el-button><el-button type="success" @click="addOutput">新增输出</el-button></template></el-dialog>
        <el-card shadow="never"><el-table :data="outputs" size="small"><el-table-column prop="name" label="名称" width="180" /><el-table-column prop="type" label="类型" width="130" /><el-table-column prop="uri" label="目标 URL" min-width="280" show-overflow-tooltip /><el-table-column label="启用" width="70"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column><el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeOutput(row.id)">删除</el-button></template></el-table-column></el-table></el-card>
      </el-tab-pane>

      <el-tab-pane label="解析规则" name="rules">
        <div style="margin-bottom:12px"><el-button type="primary" @click="showRuleDialog = true">新增解析规则</el-button><el-button @click="loadParseRules">刷新</el-button><span style="color:#909399;font-size:12px;margin-left:8px">定义「一行日志 → 字段」的提取方式，可现场用示例行验证</span></div>
        <el-card shadow="never"><el-table :data="parseRules" size="small"><el-table-column prop="name" label="规则名" width="180" /><el-table-column prop="format" label="格式" width="90" /><el-table-column prop="pattern" label="正则/描述" min-width="300" show-overflow-tooltip /><el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column><el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeParseRule(row.id)">删除</el-button></template></el-table-column></el-table></el-card>
        <el-card shadow="never" style="margin-top:14px"><template #header>解析预览（用示例行验证规则）</template><div style="display:flex;gap:10px;margin-bottom:10px"><el-select v-model="previewRuleId" placeholder="选择已有规则（留空用临时正则）" clearable style="width:260px"><el-option v-for="rule in parseRules" :key="rule.id" :label="rule.name" :value="rule.id" /></el-select><el-button type="primary" @click="doPreview">预览</el-button></div><el-input v-model="previewLine" type="textarea" :rows="2" placeholder="示例日志行" /><div v-if="previewResult" style="margin-top:12px;background:var(--ns-bg-subtle);border-radius:6px;padding:12px"><p style="margin:0 0 6px">结果：<el-tag :type="previewResult.matched ? 'success' : 'danger'" size="small">{{ previewResult.matched ? '命中' : '未命中' }}</el-tag><span v-if="previewResult.error" style="color:#f56c6c;margin-left:8px">{{ previewResult.error }}</span></p><pre class="mono" style="margin:0;font-size:12px">{{ JSON.stringify(previewResult.fields, null, 2) }}</pre></div></el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="showRender" title="vector.toml" width="720px"><el-button size="small" type="primary" @click="copyRender">复制</el-button><pre style="background:var(--ns-bg-subtle);border:1px solid var(--ns-border);border-radius:6px;padding:12px;font-size:12px;overflow:auto;max-height:440px;margin-top:10px">{{ renderText }}</pre></el-dialog>
    <el-dialog v-model="showRuleDialog" title="新增解析规则" width="560px"><el-form label-width="90px"><el-form-item label="名称"><el-input v-model="newRule.name" placeholder="如：SSHD 认证失败提取" /></el-form-item><el-form-item label="格式"><el-select v-model="newRule.format" style="width:200px"><el-option v-for="format in ['REGEX', 'JSON', 'KV', 'SYSLOG', 'CEF', 'LEEF']" :key="format" :label="format" :value="format" /></el-select></el-form-item><el-form-item v-if="newRule.format === 'REGEX'" label="正则"><el-input v-model="newRule.pattern" type="textarea" :rows="3" placeholder="命名分组正则，如：Failed password for (?&lt;user&gt;\S+) from (?&lt;srcip&gt;\d+\.\d+\.\d+\.\d+)" /></el-form-item><el-form-item label="作用于源"><el-input v-model="newRule.sourceId" placeholder="留空=全局规则" /></el-form-item><el-form-item label="启用"><el-switch v-model="newRule.enabled" /></el-form-item></el-form><template #footer><el-button @click="showRuleDialog = false">取消</el-button><el-button type="primary" @click="addParseRule">保存</el-button></template></el-dialog>
  </div>
</template>
