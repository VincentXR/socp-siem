<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import { useFormDialog } from '../composables/useFormDialog'
import { useConfirm } from '../composables/useConfirm'
import { useMutation } from '../composables/useMutation'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { tOr } from '../utils/i18nLabel'
import ActionFeedback from '../components/ActionFeedback.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
const { confirmDanger } = useConfirm()
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/checkbox/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
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
// The table skeleton is a directive, not a prop: `:loading` on `el-table` was
// silently ignored, so a slow load looked like an empty registry.
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRequest } from '../composables/useRequest'
import SevBadge from '../components/SevBadge.vue'
import PageHeader from '../components/PageHeader.vue'
import {
  updateCategory, updateDataSourceType, updateField,
  createCategory, createDataSourceType, createField, deleteCategory, deleteDataSourceType, deleteField,
  listCategories, listDataSourceTypes, listFields, SEVERITIES,
  type DataSourceType, type FieldDef, type LogCategory,
} from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()
const { columnWidth: dsColumnWidth, onHeaderDragEnd: onDsHeaderDragEnd } = useTableColumnWidths('meta-datasource-types')
const { columnWidth: categoryColumnWidth, onHeaderDragEnd: onCategoryHeaderDragEnd } = useTableColumnWidths('meta-log-categories')
const { columnWidth: fieldColumnWidth, onHeaderDragEnd: onFieldHeaderDragEnd } = useTableColumnWidths('meta-fields')
const metaTab = ref('ds')
const editingId = ref('')
const typesRequest = useRequest<DataSourceType[]>()
const categoriesRequest = useRequest<LogCategory[]>()
const fieldsRequest = useRequest<FieldDef[]>()
const typesLoading = typesRequest.loading
const categoriesLoading = categoriesRequest.loading
const fieldsLoading = fieldsRequest.loading
const loading = computed(() => typesLoading.value || categoriesLoading.value || fieldsLoading.value)
const loadError = computed(() => [
  [t('meta.dataSourceTypes'), typesRequest.error.value?.message],
  [t('meta.logCategories'), categoriesRequest.error.value?.message],
  [t('meta.fieldDictionary'), fieldsRequest.error.value?.message],
].filter(([, error]) => error).map(([label, error]) => `${label}: ${error}`).join(' ? '))
const dataSourceTypes = computed(() => typesRequest.data.value ?? [])
const logCategories = computed(() => categoriesRequest.data.value ?? [])
const fieldDefs = computed(() => fieldsRequest.data.value ?? [])
let disposed = false
const showDsDialog = ref(false)
const showCatDialog = ref(false)
const showFieldDialog = ref(false)
const newDsType = ref({ code: '', name: '', description: '', enabled: true })
const newCategory = ref({ code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true })
const newField = ref({ fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' })

/** Per-field messages instead of one "complete the required fields" banner. */
const dsErrors = ref<Record<string, string>>({})
const categoryErrors = ref<Record<string, string>>({})
const fieldErrors = ref<Record<string, string>>({})

function requiredMessage(label: string): string { return t('forms.fieldRequired', { field: label }) }

function validateDsType(): boolean {
  dsErrors.value = {}
  if (!newDsType.value.code.trim()) dsErrors.value.code = requiredMessage(t('meta.code'))
  if (!newDsType.value.name.trim()) dsErrors.value.name = requiredMessage(t('meta.name'))
  return !Object.keys(dsErrors.value).length
}

function validateCategory(): boolean {
  categoryErrors.value = {}
  if (!newCategory.value.code.trim()) categoryErrors.value.code = requiredMessage(t('meta.code'))
  if (!newCategory.value.name.trim()) categoryErrors.value.name = requiredMessage(t('meta.name'))
  return !Object.keys(categoryErrors.value).length
}

function validateFieldDef(): boolean {
  fieldErrors.value = {}
  if (!newField.value.fieldName.trim()) fieldErrors.value.fieldName = requiredMessage(t('meta.fieldName'))
  if (!newField.value.fieldLabel.trim()) fieldErrors.value.fieldLabel = requiredMessage(t('meta.fieldLabel'))
  return !Object.keys(fieldErrors.value).length
}

function editMetadata(kind: 'ds' | 'category' | 'field', row: DataSourceType | LogCategory | FieldDef) {
  if (!canWrite.value) return
  editingId.value = row.id
  if (kind === 'ds') { newDsType.value = { ...(row as DataSourceType) }; dsErrors.value = {}; showDsDialog.value = true }
  if (kind === 'category') { newCategory.value = { ...(row as LogCategory) }; categoryErrors.value = {}; showCatDialog.value = true }
  if (kind === 'field') { newField.value = { ...(row as FieldDef) }; fieldErrors.value = {}; showFieldDialog.value = true }
}

function openNewMetadata(kind: 'ds' | 'category' | 'field') {
  if (!canWrite.value) return
  editingId.value = ''
  actionError.value = ''
  if (kind === 'ds') { newDsType.value = { code: '', name: '', description: '', enabled: true }; dsErrors.value = {}; showDsDialog.value = true }
  if (kind === 'category') { newCategory.value = { code: '', name: '', description: '', defaultSeverity: 'MEDIUM', enabled: true }; categoryErrors.value = {}; showCatDialog.value = true }
  if (kind === 'field') { newField.value = { fieldName: '', fieldLabel: '', fieldType: 'string', source: 'custom', searchable: true, aggregatable: true, stored: true, description: '' }; fieldErrors.value = {}; showFieldDialog.value = true }
}

async function loadMeta() {
  if (disposed) return
  await Promise.all([
    typesRequest.execute(signal => listDataSourceTypes({ signal })),
    categoriesRequest.execute(signal => listCategories({ signal })),
    fieldsRequest.execute(signal => listFields({ signal })),
  ])
}

async function addDsType() {
  if (!canWrite.value || !validateDsType()) return
  return mutation.run(async () => {
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
  if (!canWrite.value) return
  if (!await confirmDanger(t('meta.deleteDataSourceTypeConfirm'))) return
  await deleteDataSourceType(id)
  await loadMeta()
  })
}
async function addCategory() {
  if (!canWrite.value || !validateCategory()) return
  return mutation.run(async () => {
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
  if (!canWrite.value) return
  if (!await confirmDanger(t('meta.deleteCategoryConfirm'))) return
  await deleteCategory(id)
  await loadMeta()
  })
}
async function addField() {
  if (!canWrite.value || !validateFieldDef()) return
  return mutation.run(async () => {
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
  if (!canWrite.value) return
  if (!await confirmDanger(t('meta.deleteFieldConfirm'))) return
  await deleteField(id)
  await loadMeta()
  })
}

const showDsDialogGuard = useFormDialog(showDsDialog, () => newDsType.value, () => actionBusy.value)
const showCatDialogGuard = useFormDialog(showCatDialog, () => newCategory.value, () => actionBusy.value)
const showFieldDialogGuard = useFormDialog(showFieldDialog, () => newField.value, () => actionBusy.value)
onMounted(() => { void loadMeta() })
onUnmounted(() => {
  disposed = true
  typesRequest.cancel(); categoriesRequest.cancel(); fieldsRequest.cancel()
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.ingestAndConfig')" :title="t('meta.title')" :description="t('meta.description')">
      <template #actions>
        <el-button size="small" :loading="loading" @click="loadMeta">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('meta.readOnly') }}</div>
    <ActionFeedback :error="loadError" />
    <ActionFeedback :error="actionError" />
    <el-tabs v-model="metaTab">
      <el-tab-pane :label="t('meta.dataSourceTypes')" name="ds">
        <div class="add-bar">
          <el-button v-if="canWrite" type="primary" @click="openNewMetadata('ds')">+ {{ t('meta.addDataSourceType') }}</el-button>
          <span class="hint">{{ t('meta.registryHint') }}</span>
        </div>
        <el-dialog v-model="showDsDialog" :before-close="showDsDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addDataSourceType')" width="520px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-position="top">
            <FormGrid :columns="2">
              <FormField :label="t('meta.code')" required :hint="t('meta.codeHint')" :error="dsErrors.code">
                <el-input :disabled="Boolean(editingId)" v-model="newDsType.code" :placeholder="t('meta.syslogPlaceholder')" />
              </FormField>
              <FormField :label="t('meta.name')" required :error="dsErrors.name">
                <el-input v-model="newDsType.name" :placeholder="t('meta.syslogNamePlaceholder')" />
              </FormField>
              <FormField :label="t('meta.explanation')" full>
                <el-input v-model="newDsType.description" :placeholder="t('meta.descriptionPlaceholder')" />
              </FormField>
              <FormField :label="t('meta.enabled')">
                <el-switch v-model="newDsType.enabled" />
              </FormField>
            </FormGrid>
          </el-form>
          <template #footer><el-button @click="showDsDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addDsType">{{ t('common.save') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.registryTitle') }}</template>
          <el-table v-loading="typesLoading" :data="dataSourceTypes" size="small" border allow-drag-last-column :empty-text="t('common.empty')" @header-dragend="onDsHeaderDragEnd">
            <el-table-column prop="code" column-key="code" :label="t('meta.code')" :width="dsColumnWidth('code', 130)" />
            <el-table-column prop="name" column-key="name" :label="t('meta.name')" :width="dsColumnWidth('name', 150)" />
            <el-table-column prop="description" column-key="description" :label="t('meta.explanation')" :width="dsColumnWidth('description')" min-width="300" show-overflow-tooltip />
            <el-table-column column-key="enabled" :label="t('meta.enabled')" :width="dsColumnWidth('enabled', 65)"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column v-if="canWrite" :label="t('meta.operation')" width="130" :resizable="false"><template #default="{ row }"><el-button link size="small" @click="editMetadata('ds', row as DataSourceType)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeDsType(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.logCategories')" name="cats">
        <div class="add-bar">
          <el-button v-if="canWrite" type="primary" @click="openNewMetadata('category')">+ {{ t('meta.addLogCategory') }}</el-button>
          <span class="hint">{{ t('meta.taxonomyHint') }}</span>
        </div>
        <el-dialog v-model="showCatDialog" :before-close="showCatDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addLogCategory')" width="640px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-position="top">
            <FormGrid :columns="2">
              <FormField :label="t('meta.code')" required :hint="t('meta.codeHint')" :error="categoryErrors.code">
                <el-input :disabled="Boolean(editingId)" v-model="newCategory.code" :placeholder="t('meta.authPlaceholder')" />
              </FormField>
              <FormField :label="t('meta.name')" required :error="categoryErrors.name">
                <el-input v-model="newCategory.name" :placeholder="t('meta.name')" />
              </FormField>
              <FormField :label="t('meta.baselineSeverity')" :hint="t('meta.baselineSeverityHint')">
                <el-select v-model="newCategory.defaultSeverity"><el-option v-for="s in SEVERITIES" :key="s" :label="tOr(t, 'severities.' + s, s)" :value="s" /></el-select>
              </FormField>
              <FormField :label="t('meta.enabled')">
                <el-switch v-model="newCategory.enabled" />
              </FormField>
              <FormField :label="t('meta.explanation')" full>
                <el-input v-model="newCategory.description" :placeholder="t('meta.descriptionPlaceholder')" />
              </FormField>
            </FormGrid>
          </el-form>
          <template #footer><el-button @click="showCatDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addCategory">{{ t('common.save') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.taxonomyTitle') }}</template>
          <el-table v-loading="categoriesLoading" :data="logCategories" size="small" border allow-drag-last-column :empty-text="t('common.empty')" @header-dragend="onCategoryHeaderDragEnd">
            <el-table-column prop="code" column-key="code" :label="t('meta.code')" :width="categoryColumnWidth('code', 120)" />
            <el-table-column prop="name" column-key="name" :label="t('meta.name')" :width="categoryColumnWidth('name', 130)" />
            <el-table-column prop="description" column-key="description" :label="t('meta.explanation')" :width="categoryColumnWidth('description')" min-width="260" show-overflow-tooltip />
            <el-table-column column-key="defaultSeverity" :label="t('meta.baselineSeverity')" :width="categoryColumnWidth('defaultSeverity', 100)"><template #default="{ row }"><SevBadge :value="row.defaultSeverity" /></template></el-table-column>
            <el-table-column column-key="enabled" :label="t('meta.enabled')" :width="categoryColumnWidth('enabled', 65)"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('meta.yes') : t('meta.no') }}</el-tag></template></el-table-column>
            <el-table-column v-if="canWrite" :label="t('meta.operation')" width="130" :resizable="false"><template #default="{ row }"><el-button link size="small" @click="editMetadata('category', row as LogCategory)">{{ t('common.edit') }}</el-button><el-button link type="danger" size="small" @click="removeCategory(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane :label="t('meta.fieldDictionary')" name="fields">
        <div class="add-bar">
          <el-button v-if="canWrite" type="primary" @click="openNewMetadata('field')">+ {{ t('meta.addField') }}</el-button>
          <span class="hint">{{ t('meta.fieldHint') }}</span>
        </div>
        <el-dialog v-model="showFieldDialog" :before-close="showFieldDialogGuard.beforeClose" :title="editingId ? t('common.edit') : t('meta.addField')" width="640px"><ActionFeedback :error="actionError" />
          <el-form :disabled="actionBusy" label-position="top">
            <FormGrid :columns="2">
              <FormField :label="t('meta.fieldName')" required :hint="t('meta.fieldNameHint')" :error="fieldErrors.fieldName">
                <el-input :disabled="Boolean(editingId)" v-model="newField.fieldName" :placeholder="t('meta.fieldNamePlaceholder')" />
              </FormField>
              <FormField :label="t('meta.fieldLabel')" required :error="fieldErrors.fieldLabel">
                <el-input v-model="newField.fieldLabel" :placeholder="t('meta.fieldLabelPlaceholder')" />
              </FormField>
              <FormField :label="t('meta.dataType')">
                <el-select :disabled="Boolean(editingId)" v-model="newField.fieldType"><el-option v-for="fieldType in ['string', 'int', 'long', 'float', 'ip', 'date', 'bool', 'json']" :key="fieldType" :label="fieldType" :value="fieldType" /></el-select>
              </FormField>
              <FormField :label="t('meta.source')" :hint="t('meta.sourceHint')">
                <el-select v-model="newField.source"><el-option :label="t('meta.sourceParse')" value="parse" /><el-option :label="t('meta.sourceCustom')" value="custom" /></el-select>
              </FormField>
              <FormField :label="t('meta.indexStrategy')" :hint="t('meta.indexStrategyHint')" full>
                <el-checkbox v-model="newField.searchable">{{ t('common.search') }}</el-checkbox>
                <el-checkbox v-model="newField.aggregatable">{{ t('meta.aggregation') }}</el-checkbox>
                <el-checkbox v-model="newField.stored">{{ t('meta.storage') }}</el-checkbox>
              </FormField>
              <FormField :label="t('meta.explanation')" full>
                <el-input v-model="newField.description" :placeholder="t('meta.descriptionPlaceholder')" />
              </FormField>
            </FormGrid>
          </el-form>
          <template #footer><el-button @click="showFieldDialogGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addField">{{ t('common.save') }}</el-button></template>
        </el-dialog>
        <el-card shadow="never">
          <template #header>{{ t('meta.fieldDictionaryTitle') }}</template>
          <el-table v-loading="fieldsLoading" :data="fieldDefs" size="small" border allow-drag-last-column :empty-text="t('common.empty')" @header-dragend="onFieldHeaderDragEnd">
            <el-table-column prop="fieldName" column-key="fieldName" :label="t('meta.fieldName')" :width="fieldColumnWidth('fieldName', 130)" />
            <el-table-column prop="fieldLabel" column-key="fieldLabel" :label="t('meta.fieldLabel')" :width="fieldColumnWidth('fieldLabel', 110)" />
            <el-table-column prop="fieldType" column-key="fieldType" :label="t('meta.dataType')" :width="fieldColumnWidth('fieldType', 80)" />
            <el-table-column column-key="source" :label="t('meta.source')" :width="fieldColumnWidth('source', 100)"><template #default="{ row }"><el-tag size="small" :type="row.source === 'system' ? 'info' : row.source === 'parse' ? 'success' : 'warning'">{{ row.source === 'system' ? t('meta.sourceSystem') : row.source === 'parse' ? t('meta.sourceParse') : t('meta.sourceCustom') }}</el-tag></template></el-table-column>
            <el-table-column column-key="indexStrategy" :label="t('meta.indexStrategy')" :width="fieldColumnWidth('indexStrategy', 150)"><template #default="{ row }"><el-tag v-if="row.searchable" size="small" type="success" style="margin-right:4px">{{ t('common.search') }}</el-tag><el-tag v-if="row.aggregatable" size="small" type="warning" style="margin-right:4px">{{ t('meta.aggregation') }}</el-tag><el-tag v-if="row.stored" size="small" type="info">{{ t('meta.storage') }}</el-tag></template></el-table-column>
            <el-table-column prop="description" column-key="description" :label="t('meta.explanation')" :width="fieldColumnWidth('description')" min-width="200" show-overflow-tooltip />
            <el-table-column v-if="canWrite" :label="t('meta.operation')" width="130" :resizable="false"><template #default="{ row }"><el-button v-if="row.source !== 'system'" link size="small" @click="editMetadata('field', row as FieldDef)">{{ t('common.edit') }}</el-button><el-button v-if="row.source !== 'system'" link type="danger" size="small" @click="removeField(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>
