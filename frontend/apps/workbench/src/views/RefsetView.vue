<script setup lang="ts">
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
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { addRefEntry, createRefSet, deleteRefSet, listRefSets, type ReferenceSet } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const refSets = ref<ReferenceSet[]>([])
const entryText = ref<Record<string, string>>({})
const dialogVisible = ref(false)
const loading = ref(false)
const form = ref({ name: '', description: '', entries: '' })

async function loadRefSets() { refSets.value = await listRefSets() }
async function addRefSet() {
  if (!form.value.name.trim()) return
  const entries = form.value.entries.split(/[\n,，\s]+/).filter(Boolean)
  await createRefSet({ name: form.value.name.trim(), description: form.value.description || undefined, entries })
  form.value = { name: '', description: '', entries: '' }
  dialogVisible.value = false
  await loadRefSets()
}
async function removeRefSet(id: string) {
  if (!confirm(t('refset.confirmDelete'))) return
  await deleteRefSet(id)
  await loadRefSets()
}
async function addEntry(id: string) {
  const value = (entryText.value[id] || '').trim()
  if (!value) return
  await addRefEntry(id, value)
  entryText.value[id] = ''
  await loadRefSets()
}

onMounted(loadRefSets)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('refset.title')" :description="t('refset.description')">
      <template #actions><el-button type="primary" size="small" @click="dialogVisible = true">{{ t('refset.createSet') }}</el-button></template>
    </PageHeader>

    <el-empty v-if="!refSets.length" :description="t('refset.empty')" />
    <el-card v-for="refSet in refSets" :key="refSet.id" shadow="never" class="refset-card">
      <div class="refset-head"><div><strong>{{ refSet.name }}</strong><span class="refset-meta">{{ refSet.description || '—' }} · {{ refSet.entries.length }} {{ t('refset.itemUnit') }}</span></div><el-button link type="danger" size="small" @click="removeRefSet(refSet.id)">{{ t('common.delete') }}</el-button></div>
      <div class="refset-entries"><el-tag v-for="(entry, index) in refSet.entries.slice(0, 40)" :key="index" size="small">{{ entry }}</el-tag><span v-if="refSet.entries.length > 40" class="refset-meta">… {{ t('refset.moreItems', { count: refSet.entries.length }) }}</span></div>
      <div class="refset-add-row"><el-input v-model="entryText[refSet.id]" :placeholder="t('refset.addItem')" @keyup.enter="addEntry(refSet.id)" /><el-button size="small" @click="addEntry(refSet.id)">{{ t('common.add') }}</el-button></div>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="t('refset.createSet')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('refset.nameLabel')"><el-input v-model="form.name" :placeholder="t('refset.namePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" :placeholder="t('common.description')" /></el-form-item>
        <el-form-item :label="t('refset.initialEntries')"><el-input v-model="form.entries" type="textarea" :rows="4" :placeholder="t('refset.initialEntriesPlaceholder')" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addRefSet">{{ t('common.create') }}</el-button></template>
    </el-dialog>
  </div>
</template>
