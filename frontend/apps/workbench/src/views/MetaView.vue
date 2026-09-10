<script setup lang="ts">
import { useFormDialog } from '../composables/useFormDialog'
import { useMutation } from '../composables/useMutation'
import ActionFeedback from '../components/ActionFeedback.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
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
  updateCategory, updateDataSourceType, updateField,
  createCategory, createDataSourceType, createField, deleteCategory, deleteDataSourceType, deleteField,
  listCategories, listDataSourceTypes, listFields, SEVERITIES,
  type DataSourceType, type FieldDef, type LogCategory,
} from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
const metaTab = ref('ds')
const editingId = ref('')
function editMetadata(kind: 'ds' | 'category' | 'field', row: DataSourceType | LogCategory | FieldDef) {
  editingId.value = row.id
  if (kind === 'ds') { newDsType.value = { ...(row as DataSourceType) }; showDsDialog.value = true }
  if (kind === 'category') { newCategory.value = { ...(row as LogCategory) }; showCatDialog.value = true }
  if (kind === 'field') { newField.value = { ...(row as FieldDef) }; showFieldDialog.value = true }
}
const dataSourceTypes = ref<DataSourceType[]>([])
const logCategories = ref<LogCategory[]>([])
const fieldDefs = ref<FieldDef[]>([])
const showDsDialog = ref(false)
const showCatDialog = ref(false)
const showFieldDialog = ref(false)
const newDsType = ref({ code: '', name: '', description: '', enabled: true })
const newCategory = ref({ code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true })
const newField = ref({ fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' })

function openNewMetadata(kind: 'ds' | 'category' | 'field') {
  editingId.value = ''
  actionError.value = ''
  if (kind === 'ds') { newDsType.value = { code: '', name: '', description: '', enabled: true }; showDsDialog.value = true }
  if (kind === 'category') { newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }; showCatDialog.value = true }
  if (kind === 'field') { newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }; showFieldDialog.value = true }
}

async function loadMeta() {
  const [types, categories, fields] = await Promise.all([listDataSourceTypes(), listCategories(), listFields()])
  dataSourceTypes.value = types
  logCategories.value = categories
  fieldDefs.value = fields
}

async function addDsType() {
  return mutation.run(async () => {
  if (!(newDsType.value.code.trim() && newDsType.value.name.trim())) throw new Error(t('forms.required'))
  if (editingId.value) await updateDataSourceType(editingId.value, newDsType.value)
  else await createDataSourceType(newDsType.value)
  editingId.value = ''
  newDsType.value = { code: '', name: '', description: '', enabled: true }
  showDsDialog.value = false
  await loadMeta()
  })
}
async function removeDsType(id: string) {
  return mutation.run(async () => {
  if (!confirm(t('meta.deleteDataSourceTypeConfirm'))) return
  await deleteDataSourceType(id)
  await loadMeta()
  })
}
async function addCategory() {
  return mutation.run(async () => {
  if (!(newCategory.value.code.trim() && newCategory.value.name.trim())) throw new Error(t('forms.required'))
  if (editingId.value) await updateCategory(editingId.value, newCategory.value)
  else await createCategory(newCategory.value)
  editingId.value = ''
  newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }
  showCatDialog.value = false
  await loadMeta()
  })
}
async function removeCategory(id: string) {
  return mutation.run(async () => {
  if (!confirm(t('meta.deleteCategoryConfirm'))) return
  await deleteCategory(id)
  await loadMeta()
  })
}
async function addField() {
  return mutation.run(async () => {
  if (!(newField.value.fieldName.trim() && newField.value.fieldLabel.trim())) throw new Error(t('forms.required'))
  if (editingId.value) await updateField(editingId.value, newField.value)
  else await createField(newField.value)
  editingId.value = ''
  newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }
  showFieldDialog.value = false
  await loadMeta()
  })
}
async function removeField(id: string) {
  return mutation.run(async () => {
  if (!confirm(t('meta.deleteFieldConfirm'))) return
  await deleteField(id)
  await loadMeta()
  })
}

const showDsDialogGuard = useFormDialog(showDsDialog, () => newDsType.value, () => actionBusy.value)
const showCatDialogGuard = useFormDialog(showCatDialog, () => newCategory.value, () => actionBusy.value)
const showFieldDialogGuard = useFormDialog(showFieldDialog, () => newField.value, () => actionBusy.value)
onMounted(() => mutation.run(loadMeta))
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="actionError" />
    <el-tabs v-model="metaTab">
      <el-tab-pane :label="t('meta.dataSourceTypes')" name="ds">
        <div class="add-bar">
          <el-button type="primary" @click="openNewMetadata('ds')">+ {{ t('meta.addDataSourceType') }}</el-button>
          <span class="hint">{{ t('meta.registryHint') }}</span>
        </div>
        <el-dialog v-model="showDsDialog" :before-close="showDsDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addDataSourceType')" width="520px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-width="80px">
            <el-form-item :label="t('meta.code')"><el-input :disabled="Boolean(editingId)" v-model="newDsType.code" :placeholder="t('meta.syslogPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.name')"><el-input v-model="newDsType.name" :placeholder="t('meta.syslogNamePlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newDsType.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.enabled')"><el-switch v-model="newDsType.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showDsDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="success" :loading="actionBusy" @click="addDsType">{{ t('common.save') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.registryTitle') }}</template>
          <el-table :data="dataSourceTypes" size="small" border>
            <el-table-column prop="code" :label="t('meta.code')" width="130" />
            <el-table-column prop="name" :label="t('meta.name')" width="150" />
            <el-table-column prop="description" :label="t('meta.explanation')" min-width="300" show-overflow-tooltip />
            <el-table-column :label="t('meta.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column :label="t('meta.operation')" width="130"><template #default="{ row }"><el-button link size="small" @click="editMetadata('ds', row as DataSourceType)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeDsType(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.logCategories')" name="cats">
        <div class="add-bar">
          <el-button type="primary" @click="openNewMetadata('category')">+ {{ t('meta.addLogCategory') }}</el-button>
          <span class="hint">{{ t('meta.taxonomyHint') }}</span>
        </div>
        <el-dialog v-model="showCatDialog" :before-close="showCatDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addLogCategory')" width="520px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-width="80px">
            <el-form-item :label="t('meta.code')"><el-input :disabled="Boolean(editingId)" v-model="newCategory.code" :placeholder="t('meta.authPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.name')"><el-input v-model="newCategory.name" :placeholder="t('meta.name')" /></el-form-item>
            <el-form-item :label="t('meta.baselineSeverity')"><el-select v-model="newCategory.defaultSeverity" style="width:160px"><el-option v-for="s in SEVERITIES" :key="s" :label="t('severities.' + s) || s" :value="s" /></el-select></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newCategory.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.enabled')"><el-switch v-model="newCategory.enabled" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showCatDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="success" :loading="actionBusy" @click="addCategory">{{ t('common.save') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.taxonomyTitle') }}</template>
          <el-table :data="logCategories" size="small" border>
            <el-table-column prop="code" :label="t('meta.code')" width="120" />
            <el-table-column prop="name" :label="t('meta.name')" width="130" />
            <el-table-column prop="description" :label="t('meta.explanation')" min-width="260" show-overflow-tooltip />
            <el-table-column :label="t('meta.baselineSeverity')" width="100"><template #default="{ row }"><SevBadge :value="row.defaultSeverity" /></template></el-table-column>
            <el-table-column :label="t('meta.enabled')" width="65"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column :label="t('meta.operation')" width="130"><template #default="{ row }"><el-button link size="small" @click="editMetadata('category', row as LogCategory)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeCategory(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.fieldDictionary')" name="fields">
        <div class="add-bar">
          <el-button type="primary" @click="openNewMetadata('field')">+ {{ t('meta.addField') }}</el-button>
          <span class="hint">{{ t('meta.fieldHint') }}</span>
        </div>
        <el-dialog v-model="showFieldDialog" :before-close="showFieldDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addField')" width="540px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-width="80px">
            <el-form-item :label="t('meta.fieldName')"><el-input :disabled="Boolean(editingId)" v-model="newField.fieldName" :placeholder="t('meta.fieldNamePlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.fieldLabel')"><el-input v-model="newField.fieldLabel" :placeholder="t('meta.fieldLabelPlaceholder')" /></el-form-item>
            <el-form-item :label="t('meta.dataType')"><el-select :disabled="Boolean(editingId)" v-model="newField.fieldType" style="width:160px"><el-option v-for="fieldType in ['string', 'int', 'long', 'float', 'ip', 'date', 'bool', 'json']" :key="fieldType" :label="fieldType" :value="fieldType" /></el-select></el-form-item>
            <el-form-item :label="t('meta.source')"><el-select v-model="newField.source" style="width:160px"><el-option label="parse" value="parse" /><el-option label="custom" value="custom" /></el-select></el-form-item>
            <el-form-item :label="t('meta.indexStrategy')"><el-checkbox v-model="newField.searchable">{{ t('common.search') }}</el-checkbox><el-checkbox v-model="newField.aggregatable">{{ t('meta.aggregation') }}</el-checkbox><el-checkbox v-model="newField.stored">{{ t('meta.storage') }}</el-checkbox></el-form-item>
            <el-form-item :label="t('meta.explanation')"><el-input v-model="newField.description" :placeholder="t('meta.descriptionPlaceholder')" /></el-form-item>
          </el-form>
          <template #footer><el-button @click="showFieldDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="success" :loading="actionBusy" @click="addField">{{ t('common.save') }}</el-button></template>
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
            <el-table-column :label="t('meta.operation')" width="130"><template #default="{ row }"><el-button v-if="row.source !== 'system'" link size="small" @click="editMetadata('field', row as FieldDef)">{{ t('common.edit') }}</el-button><el-button v-if="row.source !== 'system'" link type="danger" size="small" @click="removeField(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
