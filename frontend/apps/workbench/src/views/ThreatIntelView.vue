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
import { onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import PageHeader from '../components/PageHeader.vue'
import SevBadge from '../components/SevBadge.vue'
import { useResourceList } from '../composables/useResourceList'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { threatIntelApi, type Ioc } from '../api/domains'
import { SEVERITIES } from '../api'

const tiStat = ref<{ total?: number; byType?: Record<string, number> }>({})
const iocType = ref('')
const showIocDialog = ref(false)
const newIoc = ref({ type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' })
const tiMatchValue = ref('')
const tiMatchResult = ref<{ value: string; matched: boolean; ioc?: Ioc } | null>(null)
const iocList = useResourceList<Ioc>({
  searchFields: ioc => [ioc.type, ioc.value, ioc.severity, ioc.source, ioc.description],
  filter: ioc => !iocType.value || ioc.type === iocType.value,
})
const {
  items: iocs,
  page: iocPage,
  size: iocSize,
  keyword: iocKeyword,
  loading,
  filtered: iocsFiltered,
  paged: iocsPaged,
  setItems,
  resetPage,
} = iocList
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('threat-intel')

function openIocDialog() {
  newIoc.value = { type: 'ip', value: '', severity: 'HIGH', source: 'manual', description: '', tags: '' }
  showIocDialog.value = true
}

async function loadTi() {
  resetPage()
  loading.value = true
  try {
    const [iocResult, statResult] = await Promise.allSettled([
      threatIntelApi.list(iocType.value || undefined),
      threatIntelApi.stats(),
    ])
    if (iocResult.status === 'fulfilled') setItems(iocResult.value)
    if (statResult.status === 'fulfilled') tiStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function addIoc() {
  if (!newIoc.value.value.trim()) return
  await threatIntelApi.create({
    type: newIoc.value.type, value: newIoc.value.value.trim(), severity: newIoc.value.severity,
    source: newIoc.value.source, description: newIoc.value.description || undefined,
    tags: newIoc.value.tags ? newIoc.value.tags.split(/[,，\s]+/).filter(Boolean) : [],
  })
  showIocDialog.value = false
  await loadTi()
}

async function removeIoc(id: string) {
  if (!confirm('确认删除这条威胁情报？')) return
  await threatIntelApi.remove(id)
  await loadTi()
}

async function doTiMatch() {
  if (!tiMatchValue.value.trim()) return
  try { tiMatchResult.value = await threatIntelApi.match(tiMatchValue.value.trim()) }
  catch { tiMatchResult.value = { value: tiMatchValue.value, matched: false } }
}

onMounted(loadTi)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="威胁情报" description="维护 IP、域名、URL、哈希和邮箱情报，并检查事件是否命中情报库。">
      <template #actions><el-button size="small" :loading="loading" @click="loadTi">刷新</el-button></template>
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
      <div class="ti-match-toolbar">
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
    <DataTableCard v-model:current-page="iocPage" v-model:page-size="iocSize" :total="iocsFiltered.length">
      <template #toolbar>
        <FilterToolbar :count="iocsFiltered.length">
        <el-input v-model="iocKeyword" placeholder="搜索情报值 / 来源 / 描述" clearable @input="iocPage = 1" />
        </FilterToolbar>
      </template>
      <el-table :data="iocsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd">
        <el-table-column prop="type" column-key="type" label="类型" :width="columnWidth('type', 90)" sortable />
        <el-table-column prop="value" column-key="value" label="值" :width="columnWidth('value')" min-width="160" sortable show-overflow-tooltip />
        <el-table-column prop="severity" column-key="severity" label="严重度" :width="columnWidth('severity', 90)" sortable><template #default="{ row }"><SevBadge :value="row.severity" /></template></el-table-column>
        <el-table-column prop="source" column-key="source" label="来源" :width="columnWidth('source', 100)" sortable show-overflow-tooltip />
        <el-table-column prop="description" column-key="description" label="描述" :width="columnWidth('description')" min-width="160" sortable show-overflow-tooltip />
        <el-table-column label="操作" width="80" :resizable="false"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeIoc(row.id)">删除</el-button></template></el-table-column>
      </el-table>
    </DataTableCard>
  </div>
</template>
