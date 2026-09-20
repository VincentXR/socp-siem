<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/dropdown/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus/es/components/dropdown/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import DataTableCard from '../components/DataTableCard.vue'
import FilterToolbar from '../components/FilterToolbar.vue'
import MetricCard from '../components/MetricCard.vue'
import PageHeader from '../components/PageHeader.vue'
import { useTableColumnWidths } from '../composables/useTableColumnWidths'
import { useDebouncedWatch } from '../composables/useDebouncedWatch'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useListQuery } from '../composables/useListQuery'
import { assetApi, endpointApi, type Asset, type Endpoint, type EndpointEvent } from '../api/domains'
import { useI18n } from '../composables/useI18n'
import { useWriteAccess } from '../composables/useWriteAccess'
import { useConfirm } from '../composables/useConfirm'
import { tOr } from '../utils/i18nLabel'

const { t, d } = useI18n()
const canWrite = useWriteAccess()
const { confirmDanger } = useConfirm()
const route = useRoute()

const endpointStat = ref<{ total: number; online: number; byType?: Record<string, number>; eventByType?: Record<string, number>; events?: number } | null>(null)
const loadError = ref('')
const eventsError = ref('')
const actionBusy = ref(false)
const endpointEvents = ref<EndpointEvent[]>([])
const assets = ref<Asset[]>([])
const detailOpen = ref(false)
const detailEndpoint = ref<Endpoint | null>(null)
const endpoints = ref<Endpoint[]>([])
const size = ref(10)
const endpointTotal = ref(0)
const listQuery = useListQuery({ routeName: 'endpoints', total: endpointTotal, size })
const page = listQuery.page
const keyword = listQuery.keyword
const loading = ref(false)
const latestRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('endpoints')

const relatedAsset = computed(() => {
  const endpoint = detailEndpoint.value
  if (!endpoint) return null
  return assets.value.find(asset => asset.ip === endpoint.ip || asset.name.toLowerCase() === endpoint.hostname.toLowerCase()) ?? null
})
const detailEvents = computed(() => {
  const endpoint = detailEndpoint.value
  if (!endpoint) return []
  return endpointEvents.value.filter(event => {
    const hostname = String(event.hostname ?? '').toLowerCase()
    const ip = String(event.ip ?? '').toLowerCase()
    return hostname === endpoint.hostname.toLowerCase() || ip === endpoint.ip.toLowerCase()
  }).slice(0, 20)
})

function formatTime(value: unknown): string {
  if (!value) return t('time.notAvailable')
  try { return d(String(value), 'dateTime') } catch { return String(value) }
}

function eventType(event: EndpointEvent): string {
  return String(event.type ?? event.eventType ?? 'EVENT')
}

function eventSummary(event: EndpointEvent): string {
  const ignored = new Set(['eventId', 'tenantId', 'hostname', 'ip', 'type', 'eventType', 'receivedAt'])
  return Object.entries(event)
    .filter(([key, value]) => !ignored.has(key) && value !== undefined && value !== null && value !== '')
    .slice(0, 3)
    .map(([key, value]) => `${key}: ${typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
    .join(' · ')
}

function openDetail(endpoint: Endpoint): void {
  detailEndpoint.value = endpoint
  detailOpen.value = true
}

function onEndpointCommand(command: string, endpoint: Endpoint): void {
  if (command === 'unregister') void removeEndpoint(endpoint.id)
}

async function loadEndpoints() {
  const request = latestRequest.start()
  loading.value = true
  loadError.value = ''
  eventsError.value = ''
  try {
    const [endpointResult, statResult, eventResult, assetResult] = await Promise.allSettled([
      endpointApi.list(page.value, size.value, listQuery.keywordParam.value, { signal: request.signal }), endpointApi.stats({ signal: request.signal }), endpointApi.events(1, 200, { signal: request.signal }),
      // Asset lookup enriches the drawer only; endpoint health remains usable if it is unavailable.
      assetApi.list(1, 500, undefined, { signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (endpointResult.status === 'fulfilled') {
      endpoints.value = endpointResult.value.items
      endpointTotal.value = endpointResult.value.total
    } else loadError.value = endpointResult.reason instanceof Error ? endpointResult.reason.message : String(endpointResult.reason)
    if (!request.isCurrent()) return
    if (statResult.status === 'fulfilled') endpointStat.value = statResult.value
    if (!request.isCurrent()) return
    if (eventResult.status === 'fulfilled') endpointEvents.value = eventResult.value.items
    else eventsError.value = eventResult.reason instanceof Error ? eventResult.reason.message : String(eventResult.reason)
    if (!request.isCurrent()) return
    if (assetResult.status === 'fulfilled') assets.value = assetResult.value.items
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

async function removeEndpoint(id: string) {
  if (!await confirmDanger(t('endpoints.unregisterConfirm'), { title: t('endpoints.unregister') })) return
  actionBusy.value = true
  try {
    await endpointApi.remove(id)
    if (detailEndpoint.value?.id === id) detailOpen.value = false
    await loadEndpoints()
  } catch (failure) {
    loadError.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    actionBusy.value = false
  }
}

onMounted(loadEndpoints)
watch(() => [route.query.q, route.query.page], () => { listQuery.applyRouteQuery() })
watch([page, size], () => { listQuery.sync(); void loadEndpoints() })
useDebouncedWatch(keyword, () => {
  if (page.value !== 1) page.value = 1
  else { listQuery.sync(); void loadEndpoints() }
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('endpoints.title')" :description="t('endpoints.description')">
      <template #actions><el-button size="small" :loading="loading" @click="loadEndpoints">{{ t('common.refresh') }}</el-button></template>
    </PageHeader>

    <div v-if="endpointStat" class="page-metrics">
      <MetricCard :label="t('endpoints.totalEndpoints')" tone="info">{{ endpointStat.total }}</MetricCard>
      <MetricCard :label="t('endpoints.onlineEndpoints')" tone="success">{{ endpointStat.online }}</MetricCard>
      <MetricCard :label="t('endpoints.offlineEndpoints')" tone="warning">{{ endpointStat.total - endpointStat.online }}</MetricCard>
      <MetricCard :label="t('endpoints.runtimeEvents')" tone="neutral">{{ endpointStat.events ?? endpointEvents.length }}</MetricCard>
    </div>

    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="endpointTotal" :loading="loading" :error="loadError" :retry="loadEndpoints" :empty-title="t('endpoints.agentList')" :empty-description="t('endpoints.description')">
      <template #toolbar>
        <FilterToolbar :count="endpointTotal">
        <el-input v-model="keyword" :placeholder="t('endpoints.searchPlaceholder')" clearable @input="page = 1" />
        </FilterToolbar>
      </template>
      <el-table :data="endpoints" size="small" border allow-drag-last-column @header-dragend="onHeaderDragEnd" @row-click="openDetail">
        <el-table-column prop="hostname" column-key="hostname" :label="t('endpoints.hostname')" :width="columnWidth('hostname')" min-width="180" show-overflow-tooltip />
        <el-table-column prop="ip" column-key="ip" :label="t('common.ip')" :width="columnWidth('ip', 120)" />
        <el-table-column prop="os" column-key="os" :label="t('endpoints.os')" :width="columnWidth('os', 170)" show-overflow-tooltip />
        <el-table-column prop="agentVersion" column-key="agentVersion" :label="t('endpoints.agentVersion')" :width="columnWidth('agentVersion', 120)" show-overflow-tooltip />
        <el-table-column prop="status" column-key="status" :label="t('common.status')" :width="columnWidth('status', 80)">
          <template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ tOr(t, 'statuses.' + row.status, row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="lastHeartbeat" column-key="lastHeartbeat" :label="t('endpoints.lastHeartbeat')" :width="columnWidth('lastHeartbeat', 165)">
          <template #default="{ row }"><span class="table-text">{{ formatTime(row.lastHeartbeat) }}</span></template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="130" :resizable="false">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openDetail(row as Endpoint)">{{ t('common.details') }}</el-button>
            <el-dropdown v-if="canWrite" trigger="click" @click.stop @command="command => onEndpointCommand(String(command), row as Endpoint)">
              <el-button link size="small" :loading="actionBusy">{{ t('common.more') }}</el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item command="unregister">{{ t('endpoints.unregister') }}</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </DataTableCard>

    <el-drawer v-model="detailOpen" :title="detailEndpoint?.hostname || t('endpoints.endpointDetails')" size="min(620px, 96vw)">
      <template v-if="detailEndpoint">
        <div class="endpoint-detail-status">
          <el-tag :type="detailEndpoint.status === 'ONLINE' ? 'success' : 'info'" size="small">{{ tOr(t, 'statuses.' + detailEndpoint.status, detailEndpoint.status) }}</el-tag>
          <span class="mono">{{ detailEndpoint.ip }}</span>
        </div>
        <dl class="endpoint-detail-grid">
          <dt>{{ t('endpoints.hostname') }}</dt><dd>{{ detailEndpoint.hostname }}</dd>
          <dt>{{ t('common.ip') }}</dt><dd class="mono">{{ detailEndpoint.ip }}</dd>
          <dt>{{ t('endpoints.os') }}</dt><dd>{{ detailEndpoint.os || t('time.notAvailable') }}</dd>
          <dt>{{ t('endpoints.agentVersion') }}</dt><dd>{{ detailEndpoint.agentVersion || t('time.notAvailable') }}</dd>
          <dt>{{ t('endpoints.lastHeartbeat') }}</dt><dd>{{ formatTime(detailEndpoint.lastHeartbeat) }}</dd>
        </dl>

        <section class="endpoint-detail-section">
          <div class="endpoint-detail-section-head"><strong>{{ t('endpoints.relatedAsset') }}</strong><span v-if="relatedAsset" class="mono">{{ relatedAsset.id }}</span></div>
          <div v-if="relatedAsset" class="endpoint-related-asset">
            <b>{{ relatedAsset.name }}</b><span>{{ relatedAsset.type }} · {{ t('assets.criticality') }}: {{ relatedAsset.criticality }}</span><small>{{ relatedAsset.owner || t('time.notAvailable') }}</small>
          </div>
          <p v-else class="endpoint-detail-muted">{{ t('endpoints.noRelatedAsset') }}</p>
        </section>

        <section class="endpoint-detail-section">
          <div class="endpoint-detail-section-head"><strong>{{ t('endpoints.runtimeEvents') }}</strong><span>{{ detailEvents.length }}</span></div>
          <p v-if="eventsError" class="endpoint-detail-error">{{ eventsError }}</p>
          <p v-else-if="!detailEvents.length" class="endpoint-detail-muted">{{ t('endpoints.noRuntimeEvents') }}</p>
          <div v-for="event in detailEvents" :key="String(event.eventId || `${eventType(event)}-${event.receivedAt}`)" class="endpoint-event-item">
            <div class="endpoint-event-head"><el-tag size="small" type="warning">{{ eventType(event) }}</el-tag><span class="mono">{{ formatTime(event.receivedAt) }}</span></div>
            <span v-if="eventSummary(event)" class="endpoint-event-summary">{{ eventSummary(event) }}</span>
            <details><summary>{{ t('common.details') }}</summary><pre>{{ JSON.stringify(event, null, 2) }}</pre></details>
          </div>
        </section>

        <div v-if="canWrite" class="endpoint-detail-actions"><el-button type="danger" plain :loading="actionBusy" @click="removeEndpoint(detailEndpoint.id)">{{ t('endpoints.unregister') }}</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>
