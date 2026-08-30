<script setup lang="ts">
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
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { createChannel, deleteChannel, dispatchLog, listChannels, toggleChannel, type Channel, type DispatchLogEntry } from '../api'
import { useI18n } from '../composables/useI18n'

const { t } = useI18n()

const channels = ref<Channel[]>([])
const logs = ref<DispatchLogEntry[]>([])
const dialogVisible = ref(false)
const loading = ref(false)
const form = ref({ name: '', type: 'SLACK', target: '', enabled: true, description: '' })

async function loadNotify() {
  if (loading.value) return
  loading.value = true
  try {
    const [channelResult, logResult] = await Promise.allSettled([listChannels(), dispatchLog()])
    channels.value = channelResult.status === 'fulfilled' ? channelResult.value : []
    logs.value = logResult.status === 'fulfilled' ? logResult.value : []
  } finally {
    loading.value = false
  }
}

async function addChannel() {
  if (!form.value.name.trim() || !form.value.target.trim()) return
  await createChannel({ ...form.value, name: form.value.name.trim(), target: form.value.target.trim(), description: form.value.description || undefined })
  form.value = { name: '', type: 'SLACK', target: '', enabled: true, description: '' }
  dialogVisible.value = false
  await loadNotify()
}

async function removeChannel(id: string) {
  if (!confirm(t('inline.notifyView.deleteThisNotificationChannel'))) return
  await deleteChannel(id)
  await loadNotify()
}
async function toggle(id: string) { await toggleChannel(id); await loadNotify() }

onMounted(loadNotify)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('notify.title')" :description="t('notify.description')">
      <template #actions><el-button type="primary" size="small" @click="dialogVisible = true">{{ t('notify.createChannel') }}</el-button></template>
    </PageHeader>

    <el-card shadow="never" class="notify-card">
      <template #header><span>{{ t('notify.channels') }}</span></template>
      <el-table :data="channels" size="small" border>
        <el-table-column prop="name" :label="t('common.name')" width="140" />
        <el-table-column prop="type" :label="t('common.type')" width="100" />
        <el-table-column prop="target" :label="t('inline.notifyView.target')" min-width="200" show-overflow-tooltip />
        <el-table-column :label="t('common.enable')" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
        <el-table-column :label="t('common.actions')" width="150"><template #default="{ row }"><el-button link type="primary" size="small" @click="toggle(row.id)">{{ row.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" @click="removeChannel(row.id)">{{ t('common.delete') }}</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>{{ t('inline.notifyView.dispatchLogsLiveRecords') }}</template>
      <el-table :data="logs" size="small" border>
        <el-table-column prop="ts" :label="t('common.timestamp')" width="220" />
        <el-table-column prop="channel" :label="t('inline.notifyView.channel')" width="120" />
        <el-table-column prop="type" :label="t('common.type')" width="90" />
        <el-table-column prop="ruleId" :label="t('inline.notifyView.rule')" width="140" />
        <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><el-tag :type="row.status === 'sent' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="t('notify.createChannel')" width="560px">
      <el-form label-width="90px">
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" :placeholder="t('inline.notifyView.eGSecurityOpsGroup')" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="form.type" style="width:160px"><el-option v-for="type in ['SLACK', 'WEBHOOK', 'EMAIL', 'LOG']" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item :label="t('inline.notifyView.target')"><el-input v-model="form.target" :placeholder="t('inline.notifyView.webhookUrlEmail')" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" :placeholder="t('common.description')" /></el-form-item>
        <el-form-item :label="t('common.enable')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="success" @click="addChannel">{{ t('common.create') }}</el-button></template>
    </el-dialog>
  </div>
</template>
