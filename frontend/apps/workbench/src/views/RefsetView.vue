<script setup lang="ts">
import { useFormDialog } from '../composables/useFormDialog'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import DataTableCard from '../components/DataTableCard.vue'
import { useRouter } from 'vue-router'
const router = useRouter()

import { useMutation } from '../composables/useMutation'
import { useConfirm } from '../composables/useConfirm'
import ActionFeedback from '../components/ActionFeedback.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
const { confirmDanger } = useConfirm()
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/checkbox/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElCheckbox from 'element-plus/es/components/checkbox/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElMessage from 'element-plus/es/components/message/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
import { listRules, deleteRefEntry, type RuleSpec, addRefEntry, createRefSet, deleteRefSet, listRefSets, type ReferenceSet } from '../api'
import { useI18n } from '../composables/useI18n'
import { useWriteAccess } from '../composables/useWriteAccess'

const { t } = useI18n()
const canWrite = useWriteAccess()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('refsets')

const refSets = ref<ReferenceSet[]>([])
const entryText = ref<Record<string, string>>({})
const dialogVisible = ref(false)
const loading = ref(false)
const selectedId = ref('')
const keyword = ref('')
const entrySearch = ref('')
const entryPage = ref(1)
const entrySize = ref(20)
const entrySelection = ref<string[]>([])
const importText = ref('')
const importing = ref(false)
const ruleCatalog = ref<RuleSpec[]>([])
const referencesError = ref('')
/** Background load, list actions, and each dialog keep their own error surface. */
const loadError = ref('')
const entryError = ref('')
const importError = ref('')
const createError = ref('')
const createFieldErrors = ref<Record<string, string>>({})
const selected = computed(() => refSets.value.find(item => item.id === selectedId.value))
const filteredSets = computed(() => refSets.value.filter(item => `${item.name} ${item.description}`.toLowerCase().includes(keyword.value.toLowerCase())))
const filteredEntries = computed(() => (selected.value?.entries ?? []).filter(value => value.toLowerCase().includes(entrySearch.value.toLowerCase())))
const pagedEntries = computed(() => filteredEntries.value.slice((entryPage.value - 1) * entrySize.value, entryPage.value * entrySize.value).map(value => ({ value })))
const visibleEntryValues = computed(() => pagedEntries.value.map(entry => entry.value))
function references(value: unknown, names: string[]): boolean {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  if (['inlist', 'notinlist'].includes(String(row.op)) && names.includes(String(row.value))) return true
  return Object.values(row).some(item => references(item, names))
}
const referencingRules = computed(() => selected.value ? ruleCatalog.value.filter(rule => references(rule, [selected.value!.name, selected.value!.id])) : [])
async function openSet(id: string) {
  selectedId.value = id; entrySearch.value = ''; entryPage.value = 1; entrySelection.value = []
  referencesError.value = ''; entryError.value = ''; importError.value = ''; actionError.value = ''
  try { ruleCatalog.value = await listRules() } catch (failure) { referencesError.value = String(failure) }
}

/** Moves a failure out of the shared action slot into the surface that owns it. */
function isolateError(target: { value: string }, completed: boolean): void {
  if (completed) { target.value = ''; return }
  target.value = actionError.value
  actionError.value = ''
}

async function removeEntry(value: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('refset.confirmDeleteItem', { value }))) return
  entryError.value = ''
  isolateError(entryError, await mutation.run(async () => { await deleteRefEntry(selectedId.value, value); entrySelection.value = entrySelection.value.filter(item => item !== value); await loadRefSets() }))
}
function toggleEntrySelection(value: string, checked: boolean): void {
  if (checked) entrySelection.value = [...new Set([...entrySelection.value, value])]
  else entrySelection.value = entrySelection.value.filter(item => item !== value)
}
function selectVisible(): void {
  entrySelection.value = [...new Set([...entrySelection.value, ...visibleEntryValues.value])]
}
function clearVisible(): void {
  const visible = new Set(visibleEntryValues.value)
  entrySelection.value = entrySelection.value.filter(value => !visible.has(value))
}
async function removeSelected(): Promise<void> {
  if (!canWrite.value) return
  const values = [...entrySelection.value]
  if (!values.length) return
  if (!await confirmDanger(t('refset.confirmDeleteSelected', { count: values.length }))) return
  entryError.value = ''
  const failedValues: string[] = []
  isolateError(entryError, await mutation.run(async () => {
    for (const value of values) {
      try { await deleteRefEntry(selectedId.value, value) } catch { failedValues.push(value) }
    }
    // Keep the rejected rows selected so the operator can retry them, the same
    // way a partial import leaves the failed values in the textarea.
    entrySelection.value = failedValues
    await loadRefSets()
    if (failedValues.length) throw new Error(`${t('refset.partialDelete', { removed: values.length - failedValues.length, failed: failedValues.length })}: ${failedValues.join(' · ')}`)
    ElMessage.success(t('refset.itemsDeleted', { count: values.length }))
  }))
}
async function importEntries() {
  if (!canWrite.value) return
  importError.value = ''
  isolateError(importError, await mutation.run(async () => {
    const values = [...new Set(importText.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean))]
    if (!values.length) throw new Error(t('forms.fieldRequired', { field: t('refset.initialEntries') }))
    const failedValues: string[] = []
    for (const value of values) {
      try { await addRefEntry(selectedId.value, value) } catch { failedValues.push(value) }
    }
    await loadRefSets()
    if (failedValues.length) {
      importText.value = failedValues.join('\n')
      throw new Error(t('refset.partialImport', { imported: values.length - failedValues.length, failed: failedValues.length }))
    }
    importText.value = ''; importing.value = false
    ElMessage.success(t('refset.itemsAdded', { count: values.length }))
  }))
}

const form = ref({ name: '', description: '', entries: '' })

function validateSetForm(): boolean {
  createFieldErrors.value = {}
  if (!form.value.name.trim()) createFieldErrors.value.name = t('forms.fieldRequired', { field: t('refset.nameLabel') })
  return !Object.keys(createFieldErrors.value).length
}

async function loadRefSets() {
  loading.value = true
  loadError.value = ''
  try { refSets.value = await listRefSets() }
  catch (failure) { loadError.value = String(failure) }
  finally { loading.value = false }
}
async function addRefSet() {
  if (!canWrite.value || !validateSetForm()) return
  createError.value = ''
  isolateError(createError, await mutation.run(async () => {
  const entries = form.value.entries.split(/[\n,，\s]+/).filter(Boolean)
  await createRefSet({ name: form.value.name.trim(), description: form.value.description || undefined, entries })
  form.value = { name: '', description: '', entries: '' }
  dialogVisible.value = false
  await loadRefSets()
  }))
}
async function removeRefSet(id: string) {
  if (!canWrite.value) return
  if (!await confirmDanger(t('refset.confirmDelete'))) return
  entryError.value = ''
  isolateError(entryError, await mutation.run(async () => {
  await deleteRefSet(id)
  selectedId.value = ''
  await loadRefSets()
  }))
}
async function addEntry(id: string) {
  if (!canWrite.value) return
  const value = (entryText.value[id] || '').trim()
  if (!value) return
  entryError.value = ''
  isolateError(entryError, await mutation.run(async () => {
  await addRefEntry(id, value)
  entryText.value[id] = ''
  await loadRefSets()
  }))
}

function openImport(): void {
  if (!canWrite.value) return
  importError.value = ''
  actionError.value = ''
  importing.value = true
}

function openCreateSet(): void {
  if (!canWrite.value) return
  createError.value = ''
  createFieldErrors.value = {}
  actionError.value = ''
  dialogVisible.value = true
}

const importGuard = useFormDialog(importing, () => importText.value, () => actionBusy.value)
const dialogVisibleGuard = useFormDialog(dialogVisible, () => form.value, () => actionBusy.value)
onMounted(loadRefSets)
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="loadError" />
    <ActionFeedback :error="actionError" />
    <PageHeader :title="t('refset.title')" :description="t('refset.description')">
      <template #actions><el-button v-if="canWrite" type="primary" size="small" @click="openCreateSet">{{ t('refset.createSet') }}</el-button></template>
    </PageHeader>
    <div v-if="!canWrite" class="page-readonly-hint">{{ t('refset.readOnly') }}</div>

    <el-input v-model="keyword" :placeholder="t('forms.search')" clearable />
    <el-table v-loading="loading" :data="filteredSets" border allow-drag-last-column style="margin-top:16px" @header-dragend="onHeaderDragEnd">
      <el-table-column prop="name" column-key="name" :label="t('common.name')" :width="columnWidth('name')" min-width="200" />
      <el-table-column prop="description" column-key="description" :label="t('common.description')" :width="columnWidth('description')" min-width="200" show-overflow-tooltip />
      <el-table-column column-key="entries" :label="t('forms.entries')" :width="columnWidth('entries', 120)"><template #default="{ row }">{{ row.entries.length }}</template></el-table-column>
      <el-table-column :label="t('common.actions')" width="120" :resizable="false"><template #default="{ row }"><el-button link @click="openSet(row.id)">{{ t('forms.entries') }}</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !loadError && !actionError && !filteredSets.length" :description="t('refset.empty')" />
    <el-drawer :model-value="Boolean(selectedId)" :title="selected?.name || t('forms.entries')" size="min(760px, 96vw)" @update:model-value="value => { if (!value) selectedId = '' }">
      <template v-if="selected">
        <ActionFeedback :error="entryError" /><el-input v-model="entrySearch" :placeholder="t('forms.search')" clearable @input="entryPage = 1" />
        <div v-if="canWrite" class="section-toolbar"><el-input v-model="entryText[selected.id]" :placeholder="t('refset.addItem')" @keyup.enter="addEntry(selected.id)" /><el-button :loading="actionBusy" @click="addEntry(selected.id)">{{ t('common.add') }}</el-button><el-button @click="openImport">{{ t('forms.import') }}</el-button></div>
        <div class="refset-selection-toolbar"><span>{{ t('refset.visibleCount', { count: filteredEntries.length }) }} · {{ t('refset.selectedCount', { count: entrySelection.length }) }}</span><el-button v-if="canWrite" link size="small" :disabled="!visibleEntryValues.length" @click="selectVisible">{{ t('refset.selectVisible') }}</el-button><el-button v-if="canWrite" link size="small" :disabled="!visibleEntryValues.length" @click="clearVisible">{{ t('refset.clearVisible') }}</el-button><el-button v-if="canWrite && entrySelection.length" link type="danger" size="small" :disabled="actionBusy" @click="removeSelected">{{ t('refset.deleteSelected', { count: entrySelection.length }) }}</el-button></div>
        <DataTableCard v-model:current-page="entryPage" v-model:page-size="entrySize" :total="filteredEntries.length" :loading="loading"><el-table :data="pagedEntries"><el-table-column v-if="canWrite" width="48"><template #default="{ row }"><el-checkbox :model-value="entrySelection.includes(row.value)" @update:model-value="value => toggleEntrySelection(row.value, Boolean(value))" /></template></el-table-column><el-table-column prop="value" :label="t('refset.entryValue')" /><el-table-column v-if="canWrite" width="100"><template #default="{ row }"><el-button link type="danger" :disabled="actionBusy" @click="removeEntry(row.value)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></DataTableCard>
        <h3>{{ t('forms.references') }}</h3><ActionFeedback :error="referencesError" />
        <p v-for="rule in referencingRules" :key="String(rule.id)"><el-button link @click="router.push({ name: 'rule-edit', params: { ruleId: String(rule.id) } })">{{ rule.name }}</el-button></p>
        <p v-if="!referencesError && !referencingRules.length">{{ t('forms.empty') }}</p>
        <el-button v-if="canWrite" type="danger" plain :disabled="Boolean(referencesError) || referencingRules.length > 0 || actionBusy" @click="removeRefSet(selected.id)">{{ t('common.delete') }}</el-button>
      </template>
    </el-drawer>
    <el-dialog v-model="importing" :before-close="importGuard.beforeClose" :title="t('forms.import')" width="640px" append-to-body :close-on-click-modal="false"><ActionFeedback :error="importError" /><el-input v-model="importText" type="textarea" :rows="10" :placeholder="t('refset.initialEntriesPlaceholder')" /><template #footer><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="importEntries">{{ t('forms.import') }}</el-button></template></el-dialog>

    <el-dialog v-model="dialogVisible" :before-close="dialogVisibleGuard.beforeClose" :title="t('refset.createSet')" width="520px"><ActionFeedback :error="createError" />
      <el-form :disabled="actionBusy || !canWrite" label-position="top">
        <FormGrid :columns="1">
          <FormField :label="t('refset.nameLabel')" required :hint="t('refset.nameHint')" :error="createFieldErrors.name">
            <el-input v-model="form.name" :placeholder="t('refset.namePlaceholder')" />
          </FormField>
          <FormField :label="t('common.description')">
            <el-input v-model="form.description" :placeholder="t('common.description')" />
          </FormField>
          <FormField :label="t('refset.initialEntries')" :hint="t('refset.initialEntriesHint')">
            <el-input v-model="form.entries" type="textarea" :rows="4" :placeholder="t('refset.initialEntriesPlaceholder')" />
          </FormField>
        </FormGrid>
      </el-form>
      <template #footer><el-button @click="dialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addRefSet">{{ t('common.create') }}</el-button></template>
    </el-dialog>
  </div>
</template>
