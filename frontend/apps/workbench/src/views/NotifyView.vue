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
import { createChannel, deleteChannel, dispatchLog, listChannels, toggleChannel, type Channel } from '../api'

const channels = ref<Channel[]>([])
const logs = ref<Array<Record<string, unknown>>>([])
const dialogVisible = ref(false)
const loading = ref(false)
const form = ref({ name: '', type: 'SLACK', target: '', enabled: true, description: '' })

async function loadNotify() {
  if (loading.value) return
  loading.value = true
  try {
    const [channelResult, logResult] = await Promise.allSettled([listChannels(), dispatchLog()])
    channels.value = channelResult.status === 'fulfilled' ? channelResult.value : []
    logs.value = logResult.status === 'fulfilled' ? logResult.value as Array<Record<string, unknown>> : []
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
  if (!confirm('确认删除这个通知渠道？')) return
  await deleteChannel(id)
  await loadNotify()
}
async function toggle(id: string) { await toggleChannel(id); await loadNotify() }

onMounted(loadNotify)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader title="通知集成" description="配置告警通知渠道，并追踪最近的分发结果。">
      <template #actions><el-button type="primary" size="small" @click="dialogVisible = true">新增通知渠道</el-button></template>
    </PageHeader>

    <el-card shadow="never" class="notify-card">
      <template #header><span>通知渠道</span></template>
      <el-table :data="channels" size="small" border>
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="target" label="目标" min-width="200" show-overflow-tooltip />
        <el-table-column label="启用" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="150"><template #default="{ row }"><el-button link type="primary" size="small" @click="toggle(row.id)">{{ row.enabled ? '停用' : '启用' }}</el-button><el-button link type="danger" size="small" @click="removeChannel(row.id)">删除</el-button></template></el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>分发日志（告警触发后实时写入）</template>
      <el-table :data="logs" size="small" border>
        <el-table-column prop="ts" label="时间" width="220" />
        <el-table-column prop="channel" label="渠道" width="120" />
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column prop="ruleId" label="规则" width="140" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'sent' ? 'success' : row.status === 'failed' ? 'danger' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新增通知渠道" width="560px">
      <el-form label-width="80px">
        <el-form-item label="渠道名"><el-input v-model="form.name" placeholder="如 安全群" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type" style="width:160px"><el-option v-for="type in ['SLACK', 'WEBHOOK', 'EMAIL']" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item label="目标"><el-input v-model="form.target" placeholder="Webhook URL / 邮箱" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" placeholder="描述（可选）" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="success" @click="addChannel">新增渠道</el-button></template>
    </el-dialog>
  </div>
</template>
