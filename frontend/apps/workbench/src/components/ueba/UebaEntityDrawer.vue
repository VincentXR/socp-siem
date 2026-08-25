<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/descriptions/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/progress/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import { ElDescriptions, ElDescriptionsItem } from 'element-plus/es/components/descriptions/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElProgress from 'element-plus/es/components/progress/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import SevBadge from '../SevBadge.vue'
import type { RiskEntity } from '../../api'

defineProps<{ modelValue: boolean; entity: RiskEntity | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'go-alarms': [] }>()

function riskColor(level: string) {
  return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[level] ?? '#909399'
}
function fmtTime(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
</script>

<template>
  <el-drawer :model-value="modelValue" size="480px" :title="entity?.entity ?? '实体画像'" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="entity">
      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
        <span class="risk-pill lg" :style="{ background: riskColor(entity.level) }">{{ entity.risk }}</span>
        <div>
          <div style="font-weight:600" class="mono">{{ entity.entity }}</div>
          <div style="font-size:12px;color:#909399">{{ entity.level }} · {{ entity.alerts }} 条告警</div>
        </div>
        <el-tag v-if="entity.critical" type="danger" effect="dark" style="margin-left:auto">核心资产</el-tag>
      </div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="最高级别"><SevBadge :value="entity.maxSeverity" /></el-descriptions-item>
        <el-descriptions-item label="首次出现">{{ fmtTime(entity.firstSeen) }}</el-descriptions-item>
        <el-descriptions-item label="最近活动">{{ fmtTime(entity.lastSeen) }}</el-descriptions-item>
      </el-descriptions>
      <h4 style="margin:16px 0 8px">ATT&CK 技术分布</h4>
      <el-table :data="entity.mitre" size="small" border>
        <el-table-column prop="technique" label="技术" width="120" />
        <el-table-column prop="count" label="次数" width="80" />
        <el-table-column label="占比">
          <template #default="{ row }"><el-progress :percentage="Math.round(row.count / Math.max(entity.alerts, 1) * 100)" :stroke-width="10" /></template>
        </el-table-column>
      </el-table>
      <h4 style="margin:16px 0 8px">触发最多的规则</h4>
      <el-table :data="entity.topRules" size="small" border>
        <el-table-column prop="rule" label="规则" min-width="180" show-overflow-tooltip />
        <el-table-column prop="count" label="次数" width="80" />
      </el-table>
      <div style="margin-top:16px"><el-button type="primary" plain @click="emit('go-alarms')">查看该实体全部告警</el-button></div>
    </div>
  </el-drawer>
</template>
