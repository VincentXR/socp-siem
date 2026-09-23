<script setup lang="ts">
import { useWriteAccess } from '../composables/useWriteAccess'
const canWrite = useWriteAccess()
import ElMessage from 'element-plus/es/components/message/index.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import { useFormDialog } from '../composables/useFormDialog'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useConfirm } from '../composables/useConfirm'
import { useMutation } from '../composables/useMutation'
import { useLatestRequest } from '../composables/useLatestRequest'
import { tOr } from '../utils/i18nLabel'
import ActionFeedback from '../components/ActionFeedback.vue'
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
const { confirmDanger } = useConfirm()
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/loading/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import { ElForm, ElFormItem } from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { vLoading } from 'element-plus/es/components/loading/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import FormField from '../components/FormField.vue'
import FormGrid from '../components/FormGrid.vue'
import FormSection from '../components/FormSection.vue'
import { updateChannel, testChannel, createChannel, deleteChannel, dispatchLog, listChannels, toggleChannel, type Channel, type DispatchLogEntry } from '../api'
import { useI18n } from '../composables/useI18n'

const { t, d } = useI18n()
const latestRead = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('notify-channels')

const channelTypes = ['SLACK', 'WEBHOOK', 'DINGTALK', 'WECOM', 'WECHAT', 'EMAIL', 'LOG'] as const
const channels = ref<Channel[]>([])
const editingId = ref('')
const keyword = ref('')
const filteredChannels = computed(() => channels.value.filter(channel => [channel.name, channel.type, channelTypeLabel(channel.type)].join(' ').toLowerCase().includes(keyword.value.toLowerCase())))
const targetLabel = computed(() => form.value.type === 'EMAIL'
  ? t('notify.recipient')
  : form.value.type === 'LOG' ? t('notify.localDestination') : t('notify.webhookEndpoint'))
const targetPlaceholder = computed(() => form.value.type === 'EMAIL' ? 'soc@example.com' : 'https://example.com/webhook')

function channelTypeLabel(type: string): string {
  return type ? tOr(t, 'notify.types.' + type, type) : '—'
}
function dispatchStatusLabel(status: string): string {
  const value = String(status || '').toLowerCase()
  return value ? tOr(t, 'notify.dispatchStatuses.' + value, status) : status
}
function dispatchStatusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  const value = String(status || '').toLowerCase()
  if (value === 'sent' || value === 'succeeded' || value === 'success') return 'success'
  if (value === 'failed' || value === 'error') return 'danger'
  if (value === 'retrying' || value === 'pending' || value === 'queued') return 'warning'
  return 'info'
}
function displayTarget(channel: Channel): string {
  if (channel.type === 'LOG') return t('notify.localDestination')
  if (channel.type === 'EMAIL') return channel.target
  if (channel.target.length <= 18) return channel.target
  return channel.target.slice(0, 10) + '…' + channel.target.slice(-6)
}
function openChannel(channel?: Channel) {
  if (!canWrite.value || actionBusy.value) return
  editingId.value = channel?.id || ''
  form.value = channel ? { ...channel } : { name: '', type: 'SLACK', target: '', enabled: false, description: '' }
  fieldErrors.value = {}
  dialogError.value = ''
  actionError.value = ''
  testState.value = 'idle'
  dialogVisible.value = true
}
async function sendTest(channel: Channel) {
  if (!canWrite.value || actionBusy.value) return
  if (!await confirmDanger(t('notify.testConfirm', { name: channel.name }))) return
  if (!canWrite.value || actionBusy.value) return
  await mutation.run(async () => {
    try { showTestResult(await testChannel(channel.id)) }
    finally { await loadNotify() }
  })
}

const logs = ref<DispatchLogEntry[]>([])
const dialogVisible = ref(false)
const loading = ref(false)
/** Background catalog refresh, kept apart from the operator's own failures. */
const loadError = ref('')
/** Failures of the dialog's own save, so a page error cannot land in a form. */
const dialogError = ref('')
const testState = ref<'idle' | 'running' | 'failed'>('idle')
const form = ref({ name: '', type: 'SLACK', target: '', enabled: true, description: '' })

/** Moves a failure out of the shared action slot into the surface that owns it. */
function isolateError(target: { value: string }, completed: boolean): void {
  if (completed) { target.value = ''; return }
  target.value = actionError.value
  actionError.value = ''
}

function onChannelTypeChange(type: string) {
  if (type === 'LOG') form.value.target = 'local'
  else if (form.value.target === 'local') form.value.target = ''
}

async function loadNotify() {
  const request = latestRead.start()
  loading.value = true
  try {
    const [channelResult, logResult] = await Promise.allSettled([
      listChannels({ signal: request.signal }), dispatchLog({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    const failures: string[] = []
    if (channelResult.status === 'fulfilled') channels.value = channelResult.value.items
    else failures.push(String(channelResult.reason))
    if (logResult.status === 'fulfilled') logs.value = logResult.value.items
    else failures.push(String(logResult.reason))
    loadError.value = failures.join(' · ')
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

/** Per-field messages so a rejected save points at the field, not just a banner. */
const fieldErrors = ref<{ name?: string; target?: string }>({})

function validateChannel(): boolean {
  fieldErrors.value = {}
  if (!form.value.name.trim()) fieldErrors.value.name = t('forms.fieldRequired', { field: t('common.name') })
  if (form.value.type !== 'LOG') {
    const target = form.value.target.trim()
    if (!target) fieldErrors.value.target = t('forms.fieldRequired', { field: targetLabel.value })
    else if (form.value.type === 'EMAIL' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(target)) fieldErrors.value.target = t('forms.recipient')
    else if (!['EMAIL', 'LOG'].includes(form.value.type)) {
      try {
        const url = new URL(target)
        if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) fieldErrors.value.target = t('forms.endpoint')
      } catch { fieldErrors.value.target = t('forms.endpoint') }
    }
  }
  return Object.keys(fieldErrors.value).length === 0
}

function resetChannelForm() {
  form.value = { name: '', type: 'SLACK', target: '', enabled: true, description: '' }
  fieldErrors.value = {}
}

/** Retain the acknowledged resource before any separate test delivery can fail. */
async function persistChannel(): Promise<string> {
  if (form.value.type === 'LOG') form.value.target = 'local'
  const payload = { ...form.value, name: form.value.name.trim(), target: form.value.target.trim() }
  const saved = editingId.value ? await updateChannel(editingId.value, payload) : await createChannel(payload)
  if (!saved?.id) throw new Error(t('notify.saveUnconfirmed'))
  editingId.value = saved.id
  form.value = { name: saved.name, type: saved.type, target: saved.target, enabled: saved.enabled, description: saved.description || '' }
  dialogVisibleGuard.markSaved()
  channels.value = [...channels.value.filter(channel => channel.id !== saved.id), saved]
  return saved.id
}

function showTestResult(result: { status: string }): void {
  if (result.status === 'logged') ElMessage.success(t('notify.testLogged'))
  else if (result.status === 'sent') ElMessage.success(t('forms.testSent'))
  else throw new Error(t('notify.testUnconfirmed'))
}

async function addChannel() {
  if (!canWrite.value || actionBusy.value) return
  if (!validateChannel()) return
  dialogError.value = ''
  isolateError(dialogError, await mutation.run(async () => {
    await persistChannel()
    resetChannelForm()
    dialogVisible.value = false
    await loadNotify()
  }))
}

/**
 * Configuration persistence and test delivery are separate acknowledged steps.
 * Retrying an unchanged saved configuration sends only a new test request.
 */
async function saveAndTestChannel() {
  if (!canWrite.value || actionBusy.value) return
  if (!validateChannel()) return
  dialogError.value = ''
  isolateError(dialogError, await mutation.run(async () => {
    const id = editingId.value && !dialogVisibleGuard.dirty.value ? editingId.value : await persistChannel()
    testState.value = 'running'
    try {
      showTestResult(await testChannel(id))
      testState.value = 'idle'
      resetChannelForm()
      dialogVisible.value = false
    } catch (failure) {
      testState.value = 'failed'
      throw failure
    } finally { await loadNotify() }
  }))
}

async function removeChannel(id: string) {
  if (!canWrite.value || actionBusy.value) return
  if (!await confirmDanger(t('notify.confirmDelete'))) return
  if (!canWrite.value || actionBusy.value) return
  return mutation.run(async () => {
    await deleteChannel(id)
    await loadNotify()
  })
}
async function toggle(id: string) {
  if (!canWrite.value || actionBusy.value) return
  await mutation.run(async () => { await toggleChannel(id); await loadNotify() })
}

const dialogVisibleGuard = useFormDialog(dialogVisible, () => form.value, () => actionBusy.value)
onMounted(loadNotify)
</script>

<template>
  <div class="page-pad view-enter">
    <ActionFeedback :error="loadError" />
    <ActionFeedback :error="actionError" />
    <PageHeader :title="t('notify.title')" :description="t('notify.description')">
      <template #actions>
        <el-button size="small" :loading="loading" @click="loadNotify">{{ t('common.refresh') }}</el-button>
        <el-button v-if="canWrite" type="primary" size="small" :disabled="actionBusy" @click="openChannel()">{{ t('notify.createChannel') }}</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="notify-card">
      <template #header><span>{{ t('notify.channels') }}</span></template>
      <el-input v-model="keyword" :placeholder="t('forms.search')" clearable style="margin-bottom:12px" /><el-table v-loading="loading" :data="filteredChannels" size="small" border allow-drag-last-column :empty-text="t('common.empty')" @header-dragend="onHeaderDragEnd">
        <el-table-column prop="name" column-key="name" :label="t('common.name')" :width="columnWidth('name', 140)" />
        <el-table-column prop="type" column-key="type" :label="t('common.type')" :width="columnWidth('type', 120)"><template #default="{ row }">{{ channelTypeLabel(row.type) }}</template></el-table-column>
        <el-table-column column-key="target" :label="t('notify.target')" :width="columnWidth('target')" min-width="200" show-overflow-tooltip><template #default="{ row }"><span class="mono">{{ displayTarget(row as Channel) }}</span></template></el-table-column>
        <el-table-column column-key="enabled" :label="t('common.enable')" :width="columnWidth('enabled', 90)"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite" :label="t('common.actions')" width="260" :resizable="false"><template #default="{ row }"><el-button link size="small" :disabled="actionBusy" @click="openChannel(row as Channel)">{{ t('common.edit') }}</el-button><el-button link size="small" :disabled="actionBusy" @click="sendTest(row as Channel)">{{ t('forms.test') }}</el-button><el-button link type="primary" size="small" :disabled="actionBusy" @click="toggle(row.id)">{{ row.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" :disabled="actionBusy" @click="removeChannel(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('notify.dispatchLogsLive') }}</template>
      <el-table v-loading="loading" :data="logs" size="small" border :empty-text="t('common.empty')">
        <el-table-column prop="ts" :label="t('common.timestamp')" width="210"><template #default="{ row }">{{ d(row.ts) }}</template></el-table-column>
        <el-table-column prop="channel" :label="t('notify.channel')" min-width="150" show-overflow-tooltip />
        <el-table-column prop="type" :label="t('common.type')" width="110"><template #default="{ row }">{{ channelTypeLabel(row.type) }}</template></el-table-column>
        <el-table-column prop="ruleId" :label="t('notify.rule')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><el-tag :type="dispatchStatusType(row.status)" size="small">{{ dispatchStatusLabel(row.status) }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :before-close="dialogVisibleGuard.beforeClose" :title="editingId ? t('common.edit') : t('notify.createChannel')" width="640px" :close-on-click-modal="false"><ActionFeedback :error="dialogError" />
      <p v-if="testState !== 'idle'" role="status" class="dialog-hint">{{ t(testState === 'running' ? 'notify.savedTesting' : 'notify.savedTestFailed') }}</p>
      <el-form :disabled="actionBusy" label-position="top">
        <FormGrid :columns="2">
          <FormField :label="t('common.name')" required :error="fieldErrors.name">
            <el-input v-model="form.name" :placeholder="t('notify.namePlaceholder')" />
          </FormField>
          <FormField :label="t('common.type')" :hint="t('notify.typeHint')">
            <el-select v-model="form.type" @change="onChannelTypeChange"><el-option v-for="type in channelTypes" :key="type" :label="channelTypeLabel(type)" :value="type" /></el-select>
          </FormField>
          <FormField v-if="form.type !== 'LOG'" :label="targetLabel" required :hint="t('notify.targetHint')" :error="fieldErrors.target" full>
            <el-input v-model="form.target" :placeholder="targetPlaceholder" />
          </FormField>
          <FormField v-else :label="t('notify.target')" full>
            <span class="mono">{{ t('notify.localDestination') }}</span>
          </FormField>
          <FormField :label="t('common.description')" full>
            <el-input v-model="form.description" :placeholder="t('common.description')" />
          </FormField>
          <FormField :label="t('common.enable')">
            <el-switch v-model="form.enabled" />
          </FormField>
        </FormGrid>
      </el-form>
      <template #footer><el-button :disabled="actionBusy" @click="dialogVisibleGuard.cancel">{{ t('common.cancel') }}</el-button><el-button v-if="canWrite" type="primary" :loading="actionBusy" @click="addChannel">{{ t('common.save') }}</el-button><el-button v-if="canWrite" plain :loading="actionBusy" @click="saveAndTestChannel">{{ t(testState === 'failed' && !dialogVisibleGuard.dirty.value ? 'notify.retryTest' : 'notify.saveAndTest') }}</el-button></template>
    </el-dialog>
  </div>
</template>
