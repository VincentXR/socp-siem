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
import { useI18n } from '../../composables/useI18n'

defineProps<{ modelValue: boolean; entity: RiskEntity | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'go-alarms': [] }>()
const { t, d } = useI18n()

function riskColor(level: string) {
  return { CRITICAL: '#f56c6c', HIGH: '#e63946', MEDIUM: '#e6a23c', LOW: '#909399', INFO: '#909399' }[level] ?? '#909399'
}
function formatTime(value: string | null) { return value ? d(value, 'dateTime') : t('time.notAvailable') }
</script>

<template>
  <el-drawer :model-value="modelValue" size="480px" :title="entity?.entity ?? t('ueba.entityProfile')" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="entity">
      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px">
        <span class="risk-pill lg" :style="{ background: riskColor(entity.level) }">{{ entity.risk }}</span>
        <div>
          <div style="font-weight:600" class="mono">{{ entity.entity }}</div>
          <div style="font-size:12px;color:#909399">{{ t('severities.' + entity.level) || entity.level }} · {{ t('ueba.alertCount', { count: entity.alerts }) }}</div>
        </div>
        <el-tag v-if="entity.critical" type="danger" effect="dark" style="margin-left:auto">{{ t('ueba.coreAsset') }}</el-tag>
      </div>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item :label="t('ueba.highestSeverity')"><SevBadge :value="entity.maxSeverity" /></el-descriptions-item>
        <el-descriptions-item :label="t('ueba.firstSeen')">{{ formatTime(entity.firstSeen) }}</el-descriptions-item>
        <el-descriptions-item :label="t('ueba.recentActivity')">{{ formatTime(entity.lastSeen) }}</el-descriptions-item>
      </el-descriptions>
      <h4 style="margin:16px 0 8px">{{ t('ueba.attackTechniqueDistribution') }}</h4>
      <el-table :data="entity.mitre" size="small" border>
        <el-table-column prop="technique" :label="t('ueba.technique')" width="120" />
        <el-table-column prop="count" :label="t('ueba.count')" width="80" />
        <el-table-column :label="t('ueba.ratio')">
          <template #default="{ row }"><el-progress :percentage="Math.round(row.count / Math.max(entity.alerts, 1) * 100)" :stroke-width="10" /></template>
        </el-table-column>
      </el-table>
      <h4 style="margin:16px 0 8px">{{ t('ueba.topTriggeredRules') }}</h4>
      <el-table :data="entity.topRules" size="small" border>
        <el-table-column prop="rule" :label="t('common.rule')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="count" :label="t('ueba.count')" width="80" />
      </el-table>
      <div style="margin-top:16px"><el-button type="primary" plain @click="emit('go-alarms')">{{ t('ueba.viewEntityAlarms') }}</el-button></div>
    </div>
  </el-drawer>
</template>
