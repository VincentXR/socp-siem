<script setup lang="ts">
import 'element-plus/es/components/alert/style/css.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import SevBadge from '../components/SevBadge.vue'
import { createIoc, deleteIoc, listIocs, tiMatch, tiStats, SEVERITIES, type Ioc } from '../api'

const iocs = ref<Ioc[]>([])
const tiStat = ref<{ total?: number; byType?: Record<string, number> }>({})
const iocType = ref('')
const iocPage = ref(1)
const iocSize = ref(10)
const showIocDialog = ref(false)
const newIoc = ref({ type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' })
const tiMatchValue = ref('')
const tiMatchResult = ref<{ value: string; matched: boolean; ioc?: Ioc } | null>(null)
const iocKeyword = ref('')

const iocsFiltered = computed(() => {
  const q = iocKeyword.value.trim().toLowerCase()
  if (!q) return iocs.value
  return iocs.value.filter(ioc => [ioc.type, ioc.value, ioc.severity, ioc.source, ioc.description]
    .some(value => String(value ?? '').toLowerCase().includes(q)))
})
const iocsPaged = computed(() => iocsFiltered.value.slice((iocPage.value - 1) * iocSize.value, iocPage.value * iocSize.value))

function openIocDialog() {
  newIoc.value = { type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' }
  showIocDialog.value = true
}

async function loadTi() {
  iocPage.value = 1
  iocs.value = await listIocs(iocType.value || undefined)
  try { tiStat.value = await tiStats() } catch { tiStat.value = {} }
}

async function addIoc() {
  if (!newIoc.value.value.trim()) return
  await createIoc({
    type: newIoc.value.type, value: newIoc.value.value.trim(), severity: newIoc.value.severity,
    source: newIoc.value.source, description: newIoc.value.description || undefined,
    tags: newIoc.value.tags ? newIoc.value.tags.split(/[,，\s]+/).filter(Boolean) : [],
  })
  showIocDialog.value = false
  await loadTi()
}

async function removeIoc(id: string) {
  await deleteIoc(id)
  await loadTi()
}

async function doTiMatch() {
  if (!tiMatchValue.value.trim()) return
  try { tiMatchResult.value = await tiMatch(tiMatchValue.value.trim()) }
  catch { tiMatchResult.value = { value: tiMatchValue.value, matched: false } }
}

onMounted(loadTi)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="威胁情报" description="维护 IP、域名、URL、哈希和邮箱情报，并检查事件是否命中情报库。">
      <template #actions><el-button size="small" @click="loadTi">刷新</el-button></template>
    </PageHeader>
    <div class="page-metrics ti-metrics">
      <el-card shadow="never" :body-style="{ padding: '12px 18px' }">
        <div style="font-size:12px;color:#909399">情报总量</div>
        <div style="font-size:22px;font-weight:700">{{ tiStat.total ?? 0 }}</div>
      </el-card>
      <el-card v-for="(count, kind) in (tiStat.byType || {})" :key="kind" shadow="never" :body-style="{ padding: '12px 18px' }">
        <div style="font-size:12px;color:#909399">{{ kind }}</div>
        <div style="font-size:22px;font-weight:700">{{ count }}</div>
      </el-card>
    </div>
    <el-card shadow="never" style="margin-bottom:14px">
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
        <el-input v-model="tiMatchValue" placeholder="匹配情报，如 185.220.101.45 或 evil-c2.com" style="width:320px" @keyup.enter="doTiMatch" />
        <el-button type="primary" @click="doTiMatch">查询命中</el-button>
        <el-select v-model="iocType" placeholder="全部类型" clearable style="width:140px" @change="loadTi">
          <el-option v-for="t in ['ip', 'domain', 'url', 'sha256', 'email']" :key="t" :label="t" :value="t" />
        </el-select>
      </div>
      <el-alert v-if="tiMatchResult" :title="tiMatchResult.matched ? `命中情报库：${tiMatchResult.ioc?.value}（${tiMatchResult.ioc?.severity}）` : '未命中情报库'" :type="tiMatchResult.matched ? 'error' : 'info'" :closable="false" style="margin-top:10px" />
    </el-card>
    <div class="add-bar">
      <el-button type="primary" @click="openIocDialog">+ 新增情报</el-button>
      <span class="hint">IP / 域名 / URL / 文件哈希 / 邮箱，命中后被规则与富化引用</span>
    </div>
    <el-dialog v-model="showIocDialog" title="新增威胁情报" width="560px">
      <el-form label-width="80px">
        <el-form-item label="情报值"><el-input v-model="newIoc.value" placeholder="如 1.2.3.4" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="newIoc.type" style="width:160px"><el-option v-for="t in ['ip', 'domain', 'url', 'sha256', 'email']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="严重度"><el-select v-model="newIoc.severity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" /></el-select></el-form-item>
        <el-form-item label="描述"><el-input v-model="newIoc.description" placeholder="描述" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="showIocDialog = false">取消</el-button><el-button type="success" @click="addIoc">新增情报</el-button></template>
    </el-dialog>
    <el-card shadow="never">
      <div class="list-toolbar">
        <el-input v-model="iocKeyword" placeholder="搜索情报值 / 来源 / 描述" clearable @input="iocPage = 1" />
        <span class="toolbar-count">共 {{ iocsFiltered.length }} 条</span>
      </div>
      <el-table :data="iocsPaged" size="small">
        <el-table-column prop="type" label="类型" width="90" sortable />
        <el-table-column prop="value" label="值" min-width="160" sortable />
        <el-table-column prop="severity" label="严重度" width="90" sortable><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="source" label="来源" width="100" sortable />
        <el-table-column prop="description" label="描述" min-width="160" sortable show-overflow-tooltip />
        <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeIoc(row.id)">删除</el-button></template></el-table-column>
      </el-table>
      <PagerBar v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocsFiltered.length" />
    </el-card>
  </div>
</template>
