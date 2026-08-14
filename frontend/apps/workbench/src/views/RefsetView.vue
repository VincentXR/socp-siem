<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { addRefEntry, createRefSet, deleteRefSet, listRefSets, type ReferenceSet } from '../api'

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
async function removeRefSet(id: string) { await deleteRefSet(id); await loadRefSets() }
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
    <PageHeader title="参考数据集" description="维护可被检测规则引用的白名单、黑名单和其他集合。">
      <template #actions><el-button type="primary" size="small" @click="dialogVisible = true">新建参考数据集</el-button></template>
    </PageHeader>

    <el-empty v-if="!refSets.length" description="暂无参考数据集" />
    <el-card v-for="refSet in refSets" :key="refSet.id" shadow="never" class="refset-card">
      <div class="refset-head"><div><strong>{{ refSet.name }}</strong><span class="refset-meta">{{ refSet.description || '—' }} · {{ refSet.entries.length }} 条</span></div><el-button link type="danger" size="small" @click="removeRefSet(refSet.id)">删除</el-button></div>
      <div class="refset-entries"><el-tag v-for="(entry, index) in refSet.entries.slice(0, 40)" :key="index" size="small">{{ entry }}</el-tag><span v-if="refSet.entries.length > 40" class="refset-meta">… 等 {{ refSet.entries.length }} 条</span></div>
      <div class="refset-add-row"><el-input v-model="entryText[refSet.id]" placeholder="追加条目" @keyup.enter="addEntry(refSet.id)" /><el-button size="small" @click="addEntry(refSet.id)">追加</el-button></div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建参考数据集" width="560px">
      <el-form label-width="80px">
        <el-form-item label="数据集名"><el-input v-model="form.name" placeholder="如 vip_users" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" placeholder="描述" /></el-form-item>
        <el-form-item label="初始条目"><el-input v-model="form.entries" type="textarea" :rows="4" placeholder="初始条目，逗号 / 换行分隔" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="success" @click="addRefSet">新建数据集</el-button></template>
    </el-dialog>
  </div>
</template>
