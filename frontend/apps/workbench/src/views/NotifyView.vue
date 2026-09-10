<script setup lang="ts">
import ElMessage from 'element-plus/es/components/message/index.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import { useFormDialog } from '../composables/useFormDialog'
import { useMutation } from '../composables/useMutation'
import ActionFeedback from '../components/ActionFeedback.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { updateChannel, testChannel, createChannel, deleteChannel, dispatchLog, listChannels, toggleChannel, type Channel, type DispatchLogEntry } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const channels = ref<Channel[]>([])
const editingId = ref('')
const keyword = ref('')
const filteredChannels = computed(() => channels.value.filter(channel => `${channel.name} ${channel.type}`.toLowerCase().includes(keyword.value.toLowerCase())))
function openChannel(channel?: Channel) {
  editingId.value = channel?.id || ''
  form.value = channel ? { ...channel } : { name: '', type: 'SLACK', target: '', enabled: false, description: '' }
  actionError.value = ''
  dialogVisible.value = true
}
async function sendTest(channel: Channel) {
  if (!confirm(t('forms.testSend') + ': ' + channel.name + '?')) return
  await mutation.run(async () => { await testChannel(channel.id); ElMessage.success(t('forms.testSent')); await loadNotify() })
}

const logs = ref<DispatchLogEntry[]>([])
const dialogVisible = ref(false)
const loading = ref(false)
const form = ref({ name: '', type: 'SLACK', target: '', enabled: true, description: '' })

async function loadNotify() {
  if (loading.value) return
  loading.value = true
  try {
    const [channelResult, logResult] = await Promise.allSettled([listChannels(), dispatchLog()])
    const failures: string[] = []
    if (channelResult.status === 'fulfilled') channels.value = channelResult.value
    else failures.push(String(channelResult.reason))
    if (logResult.status === 'fulfilled') logs.value = logResult.value
    else failures.push(String(logResult.reason))
    actionError.value = failures.join(' · ')
  } finally {
    loading.value = false
  }
}

async function addChannel() {
  return mutation.run(async () => {
  if (form.value.type === 'LOG') form.value.target = 'local'
  if (!form.value.name.trim() || !form.value.target.trim()) throw new Error(t('forms.required'))
  if (form.value.type === 'EMAIL' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.value.target.trim())) throw new Error(t('forms.recipient'))
  if (!['EMAIL', 'LOG'].includes(form.value.type)) {
    const url = new URL(form.value.target)
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw new Error(t('forms.endpoint'))
  }
  const payload = { ...form.value, name: form.value.name.trim(), target: form.value.target.trim() }
  if (editingId.value) await updateChannel(editingId.value, payload)
  else await createChannel(payload)
  form.value = { name: '', type: 'SLACK', target: '', enabled: true, description: '' }
  dialogVisible.value = false
  await loadNotify()
  })
}

async function removeChannel(id: string) {
  return mutation.run(async () => {
  if (!confirm(t('notify.confirmDelete'))) return
  await deleteChannel(id)
  await loadNotify()
  })
}
async function toggle(id: string) { await mutation.run(async () => { await toggleChannel(id); await loadNotify() }) }

const dialogVisibleGuard = useFormDialog(dialogVisible, () => form.value, () => actionBusy.value)
onMounted(loadNotify)
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="actionError" />
    <PageHeader :title="t('notify.title')" :description="t('notify.description')">
      <template #actions><el-button type="primary" size="small" @click="openChannel()">{{ t('notify.createChannel') }}</el-button></template>
    </PageHeader>

    <el-card shadow="never" class="notify-card">
      <template #header><span>{{ t('notify.channels') }}</span></template>
      <el-input v-model="keyword" :placeholder="t('forms.search')" clearable style="margin-bottom:12px" /><el-table :data="filteredChannels" size="small" border>
        <el-table-column prop="name" :label="t('common.name')" width="140" />
        <el-table-column prop="type" :label="t('common.type')" width="100" />
        <el-table-column prop="target" :label="t('notify.target')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('common.enable')" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
        <el-table-column :label="t('common.actions')" width="260"><template #default="{ row }"><el-button link size="small" @click="openChannel(row as Channel)">{{ t('common.edit') }}</el-button><el-button link size="small" :disabled="actionBusy" @click="sendTest(row as Channel)">{{ t('forms.test') }}</el-button><el-button link type="primary" size="small" :disabled="actionBusy" @click="toggle(row.id)">{{ row.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" @click="removeChannel(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('notify.dispatchLogsLive') }}</template>
      <el-table :data="logs" size="small" border>
        <el-table-column prop="ts" :label="t('common.timestamp')" width="220" />
        <el-table-column prop="channel" :label="t('notify.channel')" width="120" />
        <el-table-column prop="type" :label="t('common.type')" width="90" />
        <el-table-column prop="ruleId" :label="t('notify.rule')" width="140" />
        <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><el-tag :type="row.status === 'sent' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :before-close="dialogVisibleGuard.beforeClose" :title="editingId ? t('common.edit') : t('notify.createChannel')" width="560px" :close-on-click-modal="false"><ActionFeedback :error="actionError" />
      <el-form :disabled="actionBusy" label-width="90px">
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" :placeholder="t('notify.namePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="form.type" style="width:160px"><el-option v-for="type in ['SLACK', 'WEBHOOK', 'EMAIL', 'LOG']" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item v-if="form.type !== 'LOG'" :label="t(form.type === 'EMAIL' ? 'forms.recipient' : 'forms.endpoint')" required><el-input v-model="form.target" :placeholder="form.type === 'EMAIL' ? 'soc@example.com' : 'https://…'" /></el-form-item><p v-else>{{ t('forms.localLog') }}</p>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" :placeholder="t('common.description')" /></el-form-item>
        <el-form-item :label="t('common.enable')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button><el-button type="success" :loading="actionBusy" @click="addChannel">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>
