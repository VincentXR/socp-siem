<script setup lang="ts">
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/dropdown/style/css.mjs'
import 'element-plus/es/components/table/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import 'element-plus/es/components/tabs/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from 'element-plus/es/components/dropdown/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElTable, ElTableColumn } from 'element-plus/es/components/table/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { ElTabs, ElTabPane } from 'element-plus/es/components/tabs/index.mjs'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PagerBar from '../components/PagerBar.vue'
import ActionFeedback from '../components/ActionFeedback.vue'
import { useMutation } from '../composables/useMutation'
import { useUnsavedChanges } from '../composables/useUnsavedChanges'
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

const router = useRouter()
const endpointStat = ref<Awaited<ReturnType<typeof endpointApi.stats>> | null>(null)
const loadError = ref('')
const statsError = ref('')
const eventsError = ref('')
const assetsError = ref('')
const detailError = ref('')
const mutation = useMutation()
const { busy: actionBusy, error: actionError } = mutation
useUnsavedChanges(() => null, () => false, () => actionBusy.value)
const detailEvents = ref<EndpointEvent[]>([])
const relatedAssets = ref<Asset[]>([])
const detailOpen = ref(false)
const detailTab = ref('events')
const detailEndpoint = ref<Endpoint | null>(null)
const selectedId = computed(() => typeof route.query.endpointId === 'string' ? route.query.endpointId : '')
const detailLoading = ref(false)
const eventsLoading = ref(false)
const assetsLoading = ref(false)
const eventPage = ref(1)
const eventSize = ref(20)
const eventTotal = ref(0)
const assetPage = ref(1)
const assetSize = ref(20)
const assetTotal = ref(0)
const endpoints = ref<Endpoint[]>([])
const size = ref(10)
const endpointTotal = ref(0)
const listQuery = useListQuery({ routeName: 'endpoints', total: endpointTotal, size })
const page = listQuery.page
const keyword = listQuery.keyword
const loading = ref(false)
const latestRequest = useLatestRequest()
const detailRequest = useLatestRequest()
const eventRequest = useLatestRequest()
const assetRequest = useLatestRequest()
const { columnWidth, onHeaderDragEnd } = useTableColumnWidths('endpoints')

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
  if (!actionBusy.value) void router.push({ query: { ...route.query, endpointId: endpoint.id } })
}

function closeDetail(): void {
  if (actionBusy.value) return
  const query = { ...route.query }
  delete query.endpointId
  void router.push({ query })
}

function onEndpointCommand(command: string, endpoint: Endpoint): void {
  if (command === 'unregister') void removeEndpoint(endpoint.id)
}

async function loadDetail() {
  const request = detailRequest.start()
  eventRequest.cancel(); assetRequest.cancel()
  detailEndpoint.value = null; detailError.value = ''; detailTab.value = 'events'
  detailEvents.value = []; relatedAssets.value = []
  eventsError.value = ''; assetsError.value = ''
  eventsLoading.value = false; assetsLoading.value = false
  eventPage.value = 1; assetPage.value = 1; eventTotal.value = 0; assetTotal.value = 0
  const id = selectedId.value
  detailOpen.value = Boolean(id); detailLoading.value = Boolean(id)
  if (!id) return
  try {
    const endpoint = await endpointApi.get(id, { signal: request.signal })
    if (!request.isCurrent()) return
    detailEndpoint.value = endpoint
    void loadEvents(); void loadAssets()
  } catch (failure) {
    if (request.isCurrent()) detailError.value = String(failure)
  } finally {
    if (request.isCurrent()) detailLoading.value = false
  }
}

async function loadEvents() {
  const endpoint = detailEndpoint.value
  if (!endpoint) return
  const request = eventRequest.start()
  eventsLoading.value = true; eventsError.value = ''; detailEvents.value = []
  try {
    const result = await endpointApi.history(endpoint.id, eventPage.value, eventSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    detailEvents.value = result.items; eventTotal.value = result.total
  } catch (failure) {
    if (request.isCurrent()) eventsError.value = String(failure)
  } finally {
    if (request.isCurrent()) eventsLoading.value = false
  }
}

async function loadAssets() {
  const endpoint = detailEndpoint.value
  if (!endpoint) return
  const request = assetRequest.start()
  assetsLoading.value = true; assetsError.value = ''; relatedAssets.value = []
  try {
    const result = await assetApi.related(endpoint.ip, endpoint.hostname, assetPage.value, assetSize.value, { signal: request.signal })
    if (!request.isCurrent()) return
    relatedAssets.value = result.items; assetTotal.value = result.total
  } catch (failure) {
    if (request.isCurrent()) assetsError.value = String(failure)
  } finally {
    if (request.isCurrent()) assetsLoading.value = false
  }
}

async function loadEndpoints() {
  const request = latestRequest.start()
  loading.value = true; loadError.value = ''; statsError.value = ''
  try {
    const [endpointResult, statResult] = await Promise.allSettled([
      endpointApi.list(page.value, size.value, listQuery.keywordParam.value, { signal: request.signal }),
      endpointApi.stats({ signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    if (endpointResult.status === 'fulfilled') {
      endpoints.value = endpointResult.value.items; endpointTotal.value = endpointResult.value.total
    } else loadError.value = String(endpointResult.reason)
    if (statResult.status === 'fulfilled') endpointStat.value = statResult.value
    else { endpointStat.value = null; statsError.value = String(statResult.reason) }
  } finally {
    if (request.isCurrent()) loading.value = false
  }
}

async function removeEndpoint(id: string) {
  if (!canWrite.value || actionBusy.value) return
  if (!await confirmDanger(t('endpoints.unregisterConfirm', { id }), { title: t('endpoints.unregister') })) return
  if (!canWrite.value || actionBusy.value) return
  const removed = await mutation.run(async () => {
    await endpointApi.remove(id)
    latestRequest.cancel(); loading.value = false
    endpoints.value = endpoints.value.filter(endpoint => endpoint.id !== id)
  })
  if (!removed) return
  if (selectedId.value === id) closeDetail()
  void loadEndpoints()
}

function refresh() { void loadEndpoints(); void loadDetail() }
onMounted(loadEndpoints)
watch(selectedId, () => { void loadDetail() }, { immediate: true })
watch([eventPage, eventSize], () => { void loadEvents() })
watch([assetPage, assetSize], () => { void loadAssets() })
watch([() => route.query.q, () => route.query.page], () => {
  const before = { page: page.value, keyword: keyword.value.trim() }
  listQuery.applyRouteQuery()
  if (before.page === page.value && before.keyword !== keyword.value.trim()) void loadEndpoints()
})
watch([page, size], () => { listQuery.sync(); void loadEndpoints() })
useDebouncedWatch(keyword, () => {
  if (keyword.value.trim() === String(route.query.q ?? '').trim()) return
  if (page.value !== 1) page.value = 1
  else { listQuery.sync(); void loadEndpoints() }
})
</script>

<template>
  <div class="page-pad view-enter">
    <PageHeader :eyebrow="t('menuGroup.assetsAndIntel')" :title="t('endpoints.title')" :description="t('endpoints.description')">
      <template #actions><el-button size="small" :loading="loading" @click="refresh">{{ t('common.refresh') }}</el-button></template>
    </PageHeader>

    <div v-if="endpointStat" class="page-metrics">
      <MetricCard :label="t('endpoints.totalEndpoints')" tone="info">{{ endpointStat.total }}</MetricCard>
      <MetricCard :label="t('endpoints.onlineEndpoints')" tone="success">{{ endpointStat.online }}</MetricCard>
      <MetricCard :label="t('endpoints.offlineEndpoints')" tone="warning">{{ endpointStat.total - endpointStat.online }}</MetricCard>
      <MetricCard :label="t('endpoints.runtimeEvents')" tone="neutral">{{ endpointStat.events ?? t('time.notAvailable') }}</MetricCard>
    </div>

    <ActionFeedback :error="statsError" />
    <ActionFeedback :error="actionError" />
    <DataTableCard v-model:current-page="page" v-model:page-size="size" :total="endpointTotal" :loading="loading" :error="loadError" :retry="loadEndpoints" :empty-title="t('endpoints.agentList')" :empty-description="t('endpoints.description')">
      <template #toolbar>
        <FilterToolbar :count="endpointTotal">
        <el-input v-model="keyword" :placeholder="t('endpoints.searchPlaceholder')" clearable />
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
              <el-button link size="small" :loading="actionBusy" :disabled="actionBusy">{{ t('common.more') }}</el-button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item command="unregister">{{ t('endpoints.unregister') }}</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
    </DataTableCard>

    <el-drawer v-model="detailOpen" :title="detailEndpoint?.hostname || t('endpoints.endpointDetails')" size="min(720px, 96vw)" :before-close="closeDetail" :close-on-click-modal="!actionBusy" :close-on-press-escape="!actionBusy">
      <p v-if="detailLoading" role="status">{{ t('common.loading') }}</p>
      <ActionFeedback :error="detailError" />
      <el-button v-if="detailError" @click="loadDetail">{{ t('common.retry') }}</el-button>
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

        <el-tabs v-model="detailTab">
          <el-tab-pane name="events" :label="t('endpoints.hostnameEvents')">
        <section class="endpoint-detail-section" data-testid="endpoint-events">
          <div class="endpoint-detail-section-head"><strong>{{ t('endpoints.hostnameEvents') }}</strong><span v-if="!eventsLoading && !eventsError">{{ eventTotal }}</span></div>
          <p class="endpoint-detail-muted">{{ t('endpoints.eventMatchHint') }}</p>
          <p v-if="eventsLoading" role="status">{{ t('common.loading') }}</p>
          <template v-else-if="eventsError"><ActionFeedback :error="eventsError" /><el-button @click="loadEvents">{{ t('common.retry') }}</el-button></template>
          <p v-else-if="!detailEvents.length" class="endpoint-detail-muted">{{ t('endpoints.noRuntimeEvents') }}</p>
          <div v-for="event in detailEvents" :key="String(event.eventId || `${eventType(event)}-${event.receivedAt}`)" class="endpoint-event-item">
            <div class="endpoint-event-head"><el-tag size="small" type="warning">{{ eventType(event) }}</el-tag><span class="mono">{{ formatTime(event.receivedAt) }}</span></div>
            <span v-if="eventSummary(event)" class="endpoint-event-summary">{{ eventSummary(event) }}</span>
            <details><summary>{{ t('common.details') }}</summary><pre>{{ JSON.stringify(event, null, 2) }}</pre></details>
          </div>
          <PagerBar v-if="!eventsError && eventTotal > 0" v-model:current-page="eventPage" v-model:page-size="eventSize" :total="eventTotal" class="endpoint-detail-pager" />
        </section>

          </el-tab-pane>
          <el-tab-pane name="assets" :label="t('endpoints.relatedAsset')">
        <section class="endpoint-detail-section" data-testid="endpoint-assets">
          <div class="endpoint-detail-section-head"><strong>{{ t('endpoints.relatedAsset') }}</strong><span v-if="!assetsLoading && !assetsError">{{ assetTotal }}</span></div>
          <p class="endpoint-detail-muted">{{ t('endpoints.assetMatchHint') }}</p>
          <p v-if="assetsLoading" role="status">{{ t('common.loading') }}</p>
          <template v-else-if="assetsError"><ActionFeedback :error="assetsError" /><el-button @click="loadAssets">{{ t('common.retry') }}</el-button></template>
          <p v-else-if="!relatedAssets.length" class="endpoint-detail-muted">{{ t('endpoints.noRelatedAsset') }}</p>
          <div v-for="asset in relatedAssets" :key="asset.id" class="endpoint-related-asset">
            <el-button link type="primary" @click="router.push({ name: 'assets', query: { assetId: asset.id } })">{{ asset.name }}</el-button>
            <span class="mono">{{ asset.ip }}</span><span>{{ asset.type }} · {{ t('assets.criticality') }}: {{ asset.criticality }}</span><small>{{ asset.owner || t('time.notAvailable') }}</small>
          </div>
          <PagerBar v-if="!assetsError && assetTotal > 0" v-model:current-page="assetPage" v-model:page-size="assetSize" :total="assetTotal" class="endpoint-detail-pager" />
        </section>

          </el-tab-pane>
        </el-tabs>

      </template>
      <template v-if="detailEndpoint && canWrite" #footer>
        <ActionFeedback :error="actionError" />
        <div class="endpoint-detail-actions"><el-button type="danger" plain :loading="actionBusy" @click="removeEndpoint(detailEndpoint.id)">{{ t('endpoints.unregister') }}</el-button></div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.endpoint-detail-pager :deep(.el-pagination) { flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.endpoint-related-asset { overflow-wrap: anywhere; margin-block: 12px; }
.endpoint-related-asset :deep(.el-button) { max-width: 100%; white-space: normal; height: auto; text-align: left; }
</style>
