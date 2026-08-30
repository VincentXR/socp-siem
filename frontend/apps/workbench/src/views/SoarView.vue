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
import { createPlaybook, deletePlaybook, listPlaybookExecutions, listPlaybooks, togglePlaybook, type Playbook, type PlaybookExecution } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const playbooks = ref<Playbook[]>([])
const executions = ref<PlaybookExecution[]>([])
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

async function removePlaybook(id: string) {
  if (!confirm(t('inline.soarView.confirmDeletionOfThisPlaybook'))) return
  await deletePlaybook(id)
  await loadPlaybooks()
}
async function toggle(id: string) { await togglePlaybook(id); await loadPlaybooks() }

onMounted(loadPlaybooks)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('soar.title')" :description="t('soar.description')">
      <template #actions>
        <el-button type="primary" size="small" @click="dialogVisible = true">{{ t('soar.createPlaybook') }}</el-button>
        <el-button size="small" :loading="loading" @click="loadPlaybooks">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <el-table :data="playbooks" size="small" border>
        <el-table-column prop="name" :label="t('common.name')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="trigger" :label="t('inline.soarView.trigger')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('inline.soarView.actionChain')" min-width="260"><template #default="{ row }"><el-tag v-for="action in row.actions" :key="action" size="small" class="soar-action-tag">{{ action }}</el-tag></template></el-table-column>
        <el-table-column :label="t('common.status')" width="80"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
        <el-table-column :label="t('common.actions')" width="130"><template #default="{ row }"><el-button link size="small" @click="toggle(row.id)">{{ row.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" @click="removePlaybook(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="soar-history-card">
      <template #header>{{ t('inline.soarView.executionHistoryRecent', { p0: executions.length }) }}</template>
      <el-table :data="executionsPaged" size="small" border>
        <el-table-column prop="ts" :label="t('common.timestamp')" width="200" />
        <el-table-column prop="playbook" :label="t('inline.soarView.playbook')" min-width="140" show-overflow-tooltip />
        <el-table-column prop="trigger" :label="t('inline.soarView.trigger2')" min-width="160" show-overflow-tooltip />
        <el-table-column :label="t('inline.soarView.results')" min-width="320">
          <template #default="{ row }"><span v-for="(result, index) in (row.results || [])" :key="index" class="soar-result-tag"><el-tag size="small" :type="String(result.status).startsWith('fail') ? 'danger' : (String(result.status).startsWith('sent') || String(result.status).startsWith('created') ? 'success' : 'info')">{{ result.action }} → {{ result.status }}</el-tag></span></template>
        </el-table-column>
      </el-table>
      <PagerBar v-model:current-page="page" v-model:page-size="size" :total="executions.length" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="t('soar.createPlaybook')" width="500px">
      <el-form label-width="90px">
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('inline.soarView.trigger2')"><el-input v-model="form.trigger" /></el-form-item>
        <el-form-item :label="t('inline.soarView.actions')"><el-input v-model="form.actions" type="textarea" :rows="3" :placeholder="t('inline.soarView.oneActionPerLine')" /></el-form-item>
        <el-form-item :label="t('common.enable')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="addPlaybook">{{ t('common.create') }}</el-button></template>
    </el-dialog>
  </div>
</template>
