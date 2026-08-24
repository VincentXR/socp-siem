<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { onMounted, ref } from 'vue'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { useResourceList } from '../composables/useResourceList'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { endpointApi, type Endpoint } from '../api/domains'
import { useI18n } from '../composables/useI18n'

const { t, locale } = useI18n()

const endpointStat = ref<{ total: number; online: number; byType: Record<string, number> } | null>(null)
const endpointsList = useResourceList<Endpoint>({
  searchFields: endpoint => [endpoint.hostname, endpoint.ip, endpoint.os, endpoint.agentVersion, endpoint.status],
})
const { items: endpoints, page, size, keyword, loading, filtered: endpointsFiltered, paged: endpointsPaged, setItems } = endpointsList
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('endpoints')

async function loadEndpoints() {
  if (loading.value) return
  loading.value = true
  try {
    const [endpointResult, statResult] = await Promise.allSettled([endpointApi.list(), endpointApi.stats()])
    if (endpointResult.status === 'fulfilled') {
      setItems(endpointResult.value)
    }
    if (statResult.status === 'fulfilled') endpointStat.value = statResult.value
  } finally {
    loading.value = false
  }
}

async function removeEndpoint(id: string) {
  if (!confirm(locale.value === 'zh-CN' ? '确认注销这个端点？注销后需要 Agent 重新注册。' : 'Unregister this endpoint? Agent will need to re-register.')) return
  await endpointApi.remove(id)
  await loadEndpoints()
}

onMounted(loadEndpoints)
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :title="t('endpoints.title')" :description="t('endpoints.description')">
      <template #actions><el-button size="small" :loading="loading" @click="loadEndpoints">{{ t('common.refresh') }}</el-button></template>
    </PageHeader>

    <div v-if="endpointStat" class="page-metrics">
      <MetricCard :label="locale === 'zh-CN' ? '端点总数' : 'Total Endpoints'" tone="info">{{ endpointStat.total }}</MetricCard>
      <MetricCard :label="locale === 'zh-CN' ? '在线端点' : 'Online Endpoints'" tone="success">{{ endpointStat.online }}</MetricCard>
      <MetricCard :label="locale === 'zh-CN' ? '离线端点' : 'Offline Endpoints'" tone="warning">{{ endpointStat.total - endpointStat.online }}</MetricCard>
      <MetricCard :label="locale === 'zh-CN' ? '端点类型' : 'Endpoint Types'" tone="neutral">{{ Object.keys(endpointStat.byType || {}).length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="endpointsFiltered.length">
      <template #toolbar>
        <FilterToolbar :count="endpointsFiltered.length">
        <el-input v-model="keyword" :placeholder="locale === 'zh-CN' ? '搜索主机名 / IP / 系统' : 'Search Hostname / IP / OS'" clearable @input="page = 1" />
        </FilterToolbar>
      </template>
      <el-table :data="endpointsPaged" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @sort-change="endpointsList.onSortChange">
        <el-table-column prop="hostname" column-key="hostname" :label="t('endpoints.hostname')" :width="columnWidth('hostname', 140)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="ip" column-key="ip" :label="t('common.ip')" :width="columnWidth('ip', 120)" sortable="custom" />
        <el-table-column prop="os" column-key="os" :label="t('endpoints.os')" :width="columnWidth('os')" min-width="140" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="agentVersion" column-key="agentVersion" :label="t('endpoints.agentVersion')" :width="columnWidth('agentVersion', 120)" sortable="custom" show-overflow-tooltip />
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 80)" sortable="custom">
          <template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ t('statuses.' + row.status) || row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="80" :resizable="false">
          <template #default="{ row }"><el-button link type="danger" size="small" @click="removeEndpoint(row.id)">{{ locale === 'zh-CN' ? '注销' : 'Unregister' }}</el-button></template>
        </el-table-column>
      </el-table>
    </DataTableCard>
  </div>
</template>
