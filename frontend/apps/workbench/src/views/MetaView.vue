<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/checkbox/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCheckbox from 'element-plus/es/components/checkbox/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import SevBadge from '../components/SevBadge.vue'
import {
  createCategory, createDataSourceType, createField, deleteCategory, deleteDataSourceType, deleteField,
  listCategories, listDataSourceTypes, listFields, SEVERITIES,
  type DataSourceType, type FieldDef, type LogCategory,
} from '../api'

const metaTab = ref('ds')
const dataSourceTypes = ref<DataSourceType[]>([])
const logCategories = ref<LogCategory[]>([])
const fieldDefs = ref<FieldDef[]>([])
const showDsDialog = ref(false)
const showCatDialog = ref(false)
const showFieldDialog = ref(false)
const newDsType = ref({ code: '', name: '', description: '', enabled: true })
const newCategory = ref({ code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true })
const newField = ref({ fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' })

async function loadMeta() {
  const [types, categories, fields] = await Promise.all([listDataSourceTypes(), listCategories(), listFields()])
  dataSourceTypes.value = types
  logCategories.value = categories
  fieldDefs.value = fields
}

async function addDsType() {
  await createDataSourceType(newDsType.value)
  newDsType.value = { code: '', name: '', description: '', enabled: true }
  showDsDialog.value = false
  await loadMeta()
}
async function removeDsType(id: string) { await deleteDataSourceType(id); await loadMeta() }
async function addCategory() {
  await createCategory(newCategory.value)
  newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }
  showCatDialog.value = false
  await loadMeta()
}
async function removeCategory(id: string) { await deleteCategory(id); await loadMeta() }
async function addField() {
  await createField(newField.value)
  newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }
  showFieldDialog.value = false
  await loadMeta()
}
async function removeField(id: string) { await deleteField(id); await loadMeta() }

onMounted(loadMeta)
</script>

<template>
  <div class="page-pad view-enter">
    <el-tabs v-model="metaTab">
      <el-tab-pane label="数据源分类" name="ds">
        <div class="add-bar">
          <el-button type="primary" @click="showDsDialog = true">+ 新增数据源分类</el-button>
          <span class="hint">接入方式注册表：9 类内置 + 可扩展</span>
        </div>
        <el-dialog v-model="showDsDialog" title="新增数据源分类" width="520px">
          <el-form label-width="80px">
            <el-form-item label="编码"><el-input v-model="newDsType.code" placeholder="如 SYSLOG" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="newDsType.name" placeholder="如 Syslog 协议" /></el-form-item>
            <el-form-item label="说明"><el-input v-model="newDsType.description" placeholder="说明" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="newDsType.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showDsDialog = false">取消</el-button><el-button type="success" @click="addDsType">新增分类</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>接入方式注册表（9 类内置 + 可扩展）</template>
          <el-table :data="dataSourceTypes" size="small" border>
            <el-table-column prop="code" label="编码" width="130" />
            <el-table-column prop="name" label="名称" width="150" />
            <el-table-column prop="description" label="说明" min-width="300" show-overflow-tooltip />
            <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeDsType(row.id)">删除</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="日志类别" name="cats">
        <div class="add-bar">
          <el-button type="primary" @click="showCatDialog = true">+ 新增日志类别</el-button>
          <span class="hint">日志分类体系：对齐 SIEM Taxonomy / MITRE ATT&CK</span>
        </div>
        <el-dialog v-model="showCatDialog" title="新增日志类别" width="520px">
          <el-form label-width="80px">
            <el-form-item label="编码"><el-input v-model="newCategory.code" placeholder="如 AUTH" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="newCategory.name" placeholder="名称" /></el-form-item>
            <el-form-item label="基线级别"><el-select v-model="newCategory.defaultSeverity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="s" :value="s" /></el-select></el-form-item>
            <el-form-item label="说明"><el-input v-model="newCategory.description" placeholder="说明" /></el-form-item>
            <el-form-item label="启用"><el-switch v-model="newCategory.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showCatDialog = false">取消</el-button><el-button type="success" @click="addCategory">新增类别</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>日志分类体系（对齐 SIEM Taxonomy / MITRE ATT&CK）</template>
          <el-table :data="logCategories" size="small" border>
            <el-table-column prop="code" label="编码" width="120" />
            <el-table-column prop="name" label="名称" width="130" />
            <el-table-column prop="description" label="说明" min-width="260" show-overflow-tooltip />
            <el-table-column label="基线级别" width="100"><template #default="{ row }"><SevBadge :value="row.defaultSeverity" /></template></el-table-column>
            <el-table-column label="启用" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeCategory(row.id)">删除</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="字段字典" name="fields">
        <div class="add-bar">
          <el-button type="primary" @click="showFieldDialog = true">+ 新增字段</el-button>
          <span class="hint">统一字段语义，解析 / 检索 / 告警共用</span>
        </div>
        <el-dialog v-model="showFieldDialog" title="新增字段" width="540px">
          <el-form label-width="80px">
            <el-form-item label="字段名"><el-input v-model="newField.fieldName" placeholder="如 src_ip" /></el-form-item>
            <el-form-item label="中文名"><el-input v-model="newField.fieldLabel" placeholder="中文名" /></el-form-item>
            <el-form-item label="类型"><el-select v-model="newField.fieldType" style="width:160px"><el-option v-for="t in ['string', 'int', 'long', 'float', 'ip', 'date', 'bool', 'json']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
            <el-form-item label="来源"><el-select v-model="newField.source" style="width:160px"><el-option label="system" value="system" /><el-option label="parse" value="parse" /><el-option label="custom" value="custom" /></el-select></el-form-item>
            <el-form-item label="索引策略"><el-checkbox v-model="newField.searchable">检索</el-checkbox><el-checkbox v-model="newField.aggregatable">聚合</el-checkbox><el-checkbox v-model="newField.stored">存储</el-checkbox></el-form-item>
            <el-form-item label="说明"><el-input v-model="newField.description" placeholder="说明" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showFieldDialog = false">取消</el-button><el-button type="success" @click="addField">新增字段</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>字段字典（统一字段语义，解析/检索/告警共用）</template>
          <el-table :data="fieldDefs" size="small" border>
            <el-table-column prop="fieldName" label="字段名" width="130" />
            <el-table-column prop="fieldLabel" label="中文名" width="110" />
            <el-table-column prop="fieldType" label="类型" width="80" />
            <el-table-column prop="source" label="来源" width="80" />
            <el-table-column label="索引策略" width="150"><template #default="{ row }"><el-tag v-if="row.searchable" size="small" type="success" style="margin-right:4px">检索</el-tag><el-tag v-if="row.aggregatable" size="small" type="warning" style="margin-right:4px">聚合</el-tag><el-tag v-if="row.stored" size="small" type="info">存储</el-tag></template></el-table-column>
            <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeField(row.id)">删除</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
