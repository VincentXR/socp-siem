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
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
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
async function removeDsType(id: string) {
  if (!confirm(t('meta.deleteDataSourceTypeConfirm'))) return
  await deleteDataSourceType(id)
  await loadMeta()
}
async function addCategory() {
  await createCategory(newCategory.value)
  newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }
  showCatDialog.value = false
  await loadMeta()
}
async function removeCategory(id: string) {
  if (!confirm(t('meta.deleteCategoryConfirm'))) return
  await deleteCategory(id)
  await loadMeta()
}
async function addField() {
  await createField(newField.value)
  newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }
  showFieldDialog.value = false
  await loadMeta()
}
async function removeField(id: string) {
  if (!confirm(t('meta.deleteFieldConfirm'))) return
  await deleteField(id)
  await loadMeta()
}

onMounted(loadMeta)
</script>

<template>
  <div class="page-pad view-enter">
    <el-tabs v-model="metaTab">
      <el-tab-pane :label="t('meta.dataSourceTypes')" name="ds">
        <div class="add-bar">
          <el-button type="primary" @click="showDsDialog = true">+ {{ t('meta.addDataSourceType') }}</el-button>
          <span class="hint">{{ t('meta.registryHint') }}</span>
        </div>
        <el-dialog v-model="showDsDialog" :title="t('meta.addDataSourceType')" width="520px">
          <el-form label-width="80px">
            <el-form-item :label="t('meta.code')"><el-input v-model="newDsType.code" :placeholder="t('meta.syslogPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.name')"><el-input v-model="newDsType.name" :placeholder="t('meta.syslogNamePlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newDsType.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.enabled')"><el-switch v-model="newDsType.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showDsDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addDsType">{{ t('meta.addCategory') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.registryTitle') }}</template>
          <el-table :data="dataSourceTypes" size="small" border>
            <el-table-column prop="code" :label="t('meta.code')" width="130" />
            <el-table-column prop="name" :label="t('meta.name')" width="150" />
            <el-table-column prop="description" :label="t('meta.explanation')" min-width="300" show-overflow-tooltip />
            <el-table-column :label="t('meta.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column :label="t('meta.operation')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeDsType(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.logCategories')" name="cats">
        <div class="add-bar">
          <el-button type="primary" @click="showCatDialog = true">+ {{ t('meta.addLogCategory') }}</el-button>
          <span class="hint">{{ t('meta.taxonomyHint') }}</span>
        </div>
        <el-dialog v-model="showCatDialog" :title="t('meta.addLogCategory')" width="520px">
          <el-form label-width="80px">
            <el-form-item :label="t('meta.code')"><el-input v-model="newCategory.code" :placeholder="t('meta.authPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.name')"><el-input v-model="newCategory.name" :placeholder="t('meta.name')" /></el-form-item>
            <el-form-item :label="t('meta.baselineSeverity')"><el-select v-model="newCategory.defaultSeverity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="t('severities.' + s) || s" :value="s" /></el-select></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newCategory.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.enabled')"><el-switch v-model="newCategory.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showCatDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addCategory">{{ t('meta.addCategory') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.taxonomyTitle') }}</template>
          <el-table :data="logCategories" size="small" border>
            <el-table-column prop="code" :label="t('meta.code')" width="120" />
            <el-table-column prop="name" :label="t('meta.name')" width="130" />
            <el-table-column prop="description" :label="t('meta.explanation')" min-width="260" show-overflow-tooltip />
            <el-table-column :label="t('meta.baselineSeverity')" width="100"><template #default="{ row }"><SevBadge :value="row.defaultSeverity" /></template></el-table-column>
            <el-table-column :label="t('meta.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column :label="t('meta.operation')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeCategory(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.fieldDictionary')" name="fields">
        <div class="add-bar">
          <el-button type="primary" @click="showFieldDialog = true">+ {{ t('meta.addField') }}</el-button>
          <span class="hint">{{ t('meta.fieldHint') }}</span>
        </div>
        <el-dialog v-model="showFieldDialog" :title="t('meta.addField')" width="540px">
          <el-form label-width="80px">
            <el-form-item :label="t('meta.fieldName')"><el-input v-model="newField.fieldName" :placeholder="t('meta.fieldNamePlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.fieldLabel')"><el-input v-model="newField.fieldLabel" :placeholder="t('meta.fieldLabelPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.dataType')"><el-select v-model="newField.fieldType" style="width:160px"><el-option v-for="fieldType in ['string', 'int', 'long', 'float', 'ip', 'date', 'bool', 'json']" :key="fieldType" :label="fieldType" :value="fieldType" /></el-select></el-form-item>
            <el-form-item :label="t('meta.source')"><el-select v-model="newField.source" style="width:160px"><el-option label="system" value="system" /><el-option label="parse" value="parse" /><el-option label="custom" value="custom" /></el-select></el-form-item>
            <el-form-item :label="t('meta.indexStrategy')"><el-checkbox v-model="newField.searchable">{{ t('common.search') }}</el-checkbox><el-checkbox v-model="newField.aggregatable">{{ t('meta.aggregation') }}</el-checkbox><el-checkbox v-model="newField.stored">{{ t('meta.storage') }}</el-checkbox></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newField.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showFieldDialog = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addField">{{ t('meta.addField') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.fieldDictionaryTitle') }}</template>
          <el-table :data="fieldDefs" size="small" border>
            <el-table-column prop="fieldName" :label="t('meta.fieldName')" width="130" />
            <el-table-column prop="fieldLabel" :label="t('meta.fieldLabel')" width="110" />
            <el-table-column prop="fieldType" :label="t('meta.dataType')" width="80" />
            <el-table-column prop="source" :label="t('meta.source')" width="80" />
            <el-table-column :label="t('meta.indexStrategy')" width="150"><template #default="{ row }"><el-tag v-if="row.searchable" size="small" type="success" style="margin-right:4px">{{ t('common.search') }}</el-tag><el-tag v-if="row.aggregatable" size="small" type="warning" style="margin-right:4px">{{ t('meta.aggregation') }}</el-tag><el-tag v-if="row.stored" size="small" type="info">{{ t('meta.storage') }}</el-tag></template></el-table-column>
            <el-table-column prop="description" :label="t('meta.explanation')" min-width="200" show-overflow-tooltip />
            <el-table-column :label="t('meta.operation')" width="70"><template #default="{ row }"><el-button link type="danger" size="small" @click="removeField(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
