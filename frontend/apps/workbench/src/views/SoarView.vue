<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import PagerBar from '../components/PagerBar.vue'
import { createPlaybook, deletePlaybook, listPlaybookExecutions, listPlaybooks, togglePlaybook, type Playbook } from '../api'

const playbooks = ref<Playbook[]>([])
const executions = ref<Array<Record<string, unknown>>>([])
const page = ref(1)
const size = ref(10)
const dialogVisible = ref(false)
const loading = ref(false)
const form = ref({ name: '', trigger: '', actions: '', enabled: true })
const executionsPaged = computed(() => executions.value.slice((page.value - 1) * size.value, page.value * size.value))

async function loadPlaybooks() {
  if (loading.value) return
  loading.value = true
  try {
    const [playbookResult, executionResult] = await Promise.allSettled([listPlaybooks(), listPlaybookExecutions()])
    if (playbookResult.status === 'fulfilled') playbooks.value = playbookResult.value
    executions.value = executionResult.status === 'fulfilled' ? executionResult.value : []
  } finally {
    loading.value = false
  }
}

async function addPlaybook() {
  await createPlaybook({ name: form.value.name, trigger: form.value.trigger, actions: form.value.actions.split(/[,，\n]/).map(action => action.trim()).filter(Boolean), enabled: form.value.enabled })
  dialogVisible.value = false
  form.value = { name: '', trigger: '', actions: '', enabled: true }
  await loadPlaybooks()
}

async function removePlaybook(id: string) { await deletePlaybook(id); await loadPlaybooks() }
async function toggle(id: string) { await togglePlaybook(id); await loadPlaybooks() }

onMounted(loadPlaybooks)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="编排响应" description="管理自动化处置剧本，并查看最近的执行结果。">
      <template #actions>
        <el-button type="primary" size="small" @click="dialogVisible = true">新建剧本</el-button>
        <el-button size="small" :loading="loading" @click="loadPlaybooks">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <el-table :data="playbooks" size="small" border>
        <el-table-column prop="name" label="剧本" min-width="140" show-overflow-tooltip />
        <el-table-column prop="trigger" label="触发条件" min-width="200" show-overflow-tooltip />
        <el-table-column label="动作链" min-width="260"><template #default="{ row }"><el-tag v-for="action in row.actions" :key="action" size="small" class="soar-action-tag">{{ action }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="130"><template #default="{ row }"><el-button link size="small" @click="toggle(row.id)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" size="small" @click="removePlaybook(row.id)">删除</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="soar-history-card">
      <template #header>执行历史（最近 {{ executions.length }} 条）</template>
      <el-table :data="executionsPaged" size="small" border>
        <el-table-column prop="ts" label="时间" width="200" />
        <el-table-column prop="playbook" label="剧本" min-width="140" show-overflow-tooltip />
        <el-table-column prop="trigger" label="触发" min-width="160" show-overflow-tooltip />
        <el-table-column label="动作结果" min-width="320">
          <template #default="{ row }"><span v-for="(result, index) in (row.results as any[] || [])" :key="index" class="soar-result-tag"><el-tag size="small" :type="String(result.status).startsWith('fail') ? 'danger' : (String(result.status).startsWith('sent') || String(result.status).startsWith('created') ? 'success' : 'info')">{{ result.action }} → {{ result.status }}</el-tag></span></template>
        </el-table-column>
      </el-table>
      <PagerBar v-model:current-page="page" v-model:page-size="size" :total="executions.length" />
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建剧本" width="500px">
      <el-form label-width="80px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="触发"><el-input v-model="form.trigger" /></el-form-item>
        <el-form-item label="动作"><el-input v-model="form.actions" type="textarea" :rows="3" placeholder="每行一个动作" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="addPlaybook">创建</el-button></template>
    </el-dialog>
  </div>
</template>
