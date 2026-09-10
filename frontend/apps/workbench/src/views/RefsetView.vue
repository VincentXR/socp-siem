<script setup lang="ts">
import { useFormDialog } from '../composables/useFormDialog'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import DataTableCard from '../components/DataTableCard.vue'
import { useRouter } from 'vue-router'
const router = useRouter()

import { useMutation } from '../composables/useMutation'
import ActionFeedback from '../components/ActionFeedback.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/empty/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElEmpty from 'element-plus/es/components/empty/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { listRules, deleteRefEntry, type RuleSpec, addRefEntry, createRefSet, deleteRefSet, listRefSets, type ReferenceSet } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const refSets = ref<ReferenceSet[]>([])
const entryText = ref<Record<string, string>>({})
const dialogVisible = ref(false)
const loading = ref(false)
const selectedId = ref('')
const keyword = ref('')
const entrySearch = ref('')
const entryPage = ref(1)
const entrySize = ref(20)
const importText = ref('')
const importing = ref(false)
const ruleCatalog = ref<RuleSpec[]>([])
const referencesError = ref('')
const selected = computed(() => refSets.value.find(item => item.id === selectedId.value))
const filteredSets = computed(() => refSets.value.filter(item => `${item.name} ${item.description}`.toLowerCase().includes(keyword.value.toLowerCase())))
const filteredEntries = computed(() => (selected.value?.entries ?? []).filter(value => value.toLowerCase().includes(entrySearch.value.toLowerCase())))
function references(value: unknown, names: string[]): boolean {
  if (!value || typeof value !== 'object') return false
  const row = value as Record<string, unknown>
  if (['inlist', 'notinlist'].includes(String(row.op)) && names.includes(String(row.value))) return true
  return Object.values(row).some(item => references(item, names))
}
const referencingRules = computed(() => selected.value ? ruleCatalog.value.filter(rule => references(rule, [selected.value!.name, selected.value!.id])) : [])
async function openSet(id: string) {
  selectedId.value = id; entrySearch.value = ''; entryPage.value = 1; referencesError.value = ''
  try { ruleCatalog.value = await listRules() } catch (failure) { referencesError.value = String(failure) }
}
async function removeEntry(value: string) {
  await mutation.run(async () => { await deleteRefEntry(selectedId.value, value); await loadRefSets() })
}
async function importEntries() {
  await mutation.run(async () => {
    const values = [...new Set(importText.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean))]
    try {
      for (let index = 0; index < values.length; index++) {
        importText.value = values.slice(index).join('\n')
        await addRefEntry(selectedId.value, values[index])
      }
      importText.value = ''; importing.value = false
    } finally { await loadRefSets() }
  })
}

const form = ref({ name: '', description: '', entries: '' })

async function loadRefSets() {
  loading.value = true
  try { refSets.value = await listRefSets() }
  catch (failure) { actionError.value = String(failure) }
  finally { loading.value = false }
}
async function addRefSet() {
  return mutation.run(async () => {
  if (!form.value.name.trim()) throw new Error(t('forms.required'))
  const entries = form.value.entries.split(/[\n,，\s]+/).filter(Boolean)
  await createRefSet({ name: form.value.name.trim(), description: form.value.description || undefined, entries })
  form.value = { name: '', description: '', entries: '' }
  dialogVisible.value = false
  await loadRefSets()
  })
}
async function removeRefSet(id: string) {
  return mutation.run(async () => {
  if (!confirm(t('refset.confirmDelete'))) return
  await deleteRefSet(id)
  selectedId.value = ''
  await loadRefSets()
  })
}
async function addEntry(id: string) {
  return mutation.run(async () => {
  const value = (entryText.value[id] || '').trim()
  if (!value) return
  await addRefEntry(id, value)
  entryText.value[id] = ''
  await loadRefSets()
  })
}

const importGuard = useFormDialog(importing, () => importText.value, () => actionBusy.value)
const dialogVisibleGuard = useFormDialog(dialogVisible, () => form.value, () => actionBusy.value)
onMounted(loadRefSets)
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="actionError" />
    <PageHeader :title="t('refset.title')" :description="t('refset.description')">
      <template #actions><el-button type="primary" size="small" @click="dialogVisible = true">{{ t('refset.createSet') }}</el-button></template>
    </PageHeader>

    <el-input v-model="keyword" :placeholder="t('forms.search')" clearable />
    <el-table :data="filteredSets" style="margin-top:16px">
      <el-table-column prop="name" :label="t('common.name')" min-width="200" />
      <el-table-column prop="description" :label="t('common.description')" min-width="200" show-overflow-tooltip />
      <el-table-column :label="t('forms.entries')" width="120"><template #default="{ row }">{{ row.entries.length }}</template></el-table-column>
      <el-table-column :label="t('common.actions')" width="120"><template #default="{ row }"><el-button link @click="openSet(row.id)">{{ t('forms.entries') }}</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && !actionError && !filteredSets.length" :description="t('refset.empty')" />
    <el-drawer :model-value="Boolean(selectedId)" :title="selected?.name || t('forms.entries')" size="min(760px, 96vw)" @update:model-value="value => { if (!value) selectedId = '' }">
      <template v-if="selected">
        <ActionFeedback :error="actionError" /><el-input v-model="entrySearch" :placeholder="t('forms.search')" clearable @input="entryPage = 1" />
        <div class="section-toolbar"><el-input v-model="entryText[selected.id]" :placeholder="t('refset.addItem')" @keyup.enter="addEntry(selected.id)" /><el-button :loading="actionBusy" @click="addEntry(selected.id)">{{ t('common.add') }}</el-button><el-button @click="importing = true">{{ t('forms.import') }}</el-button></div>
        <DataTableCard v-model:current-page="entryPage" v-model:page-size="entrySize" :total="filteredEntries.length" :loading="loading"><el-table :data="filteredEntries.slice((entryPage - 1) * entrySize, entryPage * entrySize).map(value => ({ value }))"><el-table-column prop="value" :label="t('ueba.watchlistValues')" /><el-table-column width="100"><template #default="{ row }"><el-button link type="danger" :disabled="actionBusy" @click="removeEntry(row.value)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table></DataTableCard>
        <h3>{{ t('forms.references') }}</h3><ActionFeedback :error="referencesError" />
        <p v-for="rule in referencingRules" :key="String(rule.id)"><el-button link @click="router.push({ name: 'rule-edit', params: { ruleId: String(rule.id) } })">{{ rule.name }}</el-button></p>
        <p v-if="!referencesError && !referencingRules.length">{{ t('forms.empty') }}</p>
        <el-button type="danger" plain :disabled="Boolean(referencesError) || referencingRules.length > 0 || actionBusy" @click="removeRefSet(selected.id)">{{ t('common.delete') }}</el-button>
      </template>
    </el-drawer>
    <el-dialog v-model="importing" :before-close="importGuard.beforeClose" :title="t('forms.import')" width="560px" append-to-body :close-on-click-modal="false"><ActionFeedback :error="actionError" /><el-input v-model="importText" type="textarea" :rows="10" :placeholder="t('refset.initialEntriesPlaceholder')" /><template #footer><el-button type="primary" :loading="actionBusy" @click="importEntries">{{ t('forms.import') }}</el-button></template></el-dialog>

    <el-dialog v-model="dialogVisible" :before-close="dialogVisibleGuard.beforeClose" :title="t('refset.createSet')" width="560px"><ActionFeedback :error="actionError" />
      <el-form :disabled="actionBusy" label-width="90px">
        <el-form-item :label="t('refset.nameLabel')"><el-input v-model="form.name" :placeholder="t('refset.namePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" :placeholder="t('common.description')" /></el-form-item>
        <el-form-item :label="t('refset.initialEntries')"><el-input v-model="form.entries" type="textarea" :rows="4" :placeholder="t('refset.initialEntriesPlaceholder')" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="success" :loading="actionBusy" @click="addRefSet">{{ t('common.create') }}</el-button></template>
    </el-dialog>
  </div>
</template>
