<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import { getRule, getRuleRevision, listRuleRevisions, restoreRuleRevision, type RuleRevision, type RuleRevisionDetail, type RuleSpec } from '../api'
import { ApiError } from '../api/core'
import { useConfirm } from '../composables/useConfirm'
import { useI18n } from '../composables/useI18n'
import { useLatestRequest } from '../composables/useLatestRequest'

const props = defineProps<{ modelValue: boolean; ruleId: string; canRestore: boolean; hasDraft: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; busy: [value: boolean]; restored: [rule: RuleSpec] }>()
const { t, d } = useI18n()
const { confirmDanger } = useConfirm()
const reads = useLatestRequest()
const details = useLatestRequest()
const rows = ref<RuleRevision[]>([])
const page = ref(1)
const totalPages = ref(0)
const total = ref(0)
const current = ref<RuleSpec | null>(null)
const currentKnown = ref(false)
const selected = ref<RuleRevisionDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const restoring = ref(false)
const loadError = ref('')
const detailError = ref('')
const actionError = ref('')
const restoredMessage = ref('')
const open = computed({ get: () => props.modelValue, set: value => { if (!restoring.value) emit('update:modelValue', value) } })
const canRestoreSelection = computed(() => props.canRestore && selected.value && currentKnown.value && !loadError.value && !loading.value && !detailLoading.value && !restoring.value)

function close(done: () => void) { if (!restoring.value) done() }
function display(spec: RuleSpec | null) {
  if (!spec) return ''
  const { revisionToken: _token, ...content } = spec
  return JSON.stringify(content, null, 2)
}
function sourceLabel(source: string): string {
  const labels: Record<string, string> = { ADD: 'detect.historyAdded', EDIT: 'detect.historyEdited', DELETE: 'detect.historyDeleted', RESTORE: 'detect.historyRestored' }
  return labels[source] ? t(labels[source]) : source
}

async function load() {
  const request = reads.start()
  loading.value = true
  loadError.value = ''
  currentKnown.value = false
  try {
    const [head, history] = await Promise.all([
      getRule(props.ruleId, { signal: request.signal }).catch(error => {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }),
      listRuleRevisions(props.ruleId, page.value, { signal: request.signal }),
    ])
    if (!request.isCurrent()) return
    current.value = head
    currentKnown.value = true
    rows.value = history.items
    total.value = history.total
    totalPages.value = history.totalPages ?? Math.ceil(history.total / 10)
  } catch (error) {
    if (request.isCurrent()) loadError.value = error instanceof Error ? error.message : String(error)
  } finally { if (request.isCurrent()) loading.value = false }
}

async function selectRevision(revision: number) {
  if (restoring.value) return
  const request = details.start()
  selected.value = null
  detailLoading.value = true
  detailError.value = ''
  actionError.value = ''
  restoredMessage.value = ''
  try {
    const result = await getRuleRevision(props.ruleId, revision, { signal: request.signal })
    if (request.isCurrent()) selected.value = result
  } catch (error) {
    if (request.isCurrent()) detailError.value = error instanceof Error ? error.message : String(error)
  } finally { if (request.isCurrent()) detailLoading.value = false }
}

async function restore() {
  if (!canRestoreSelection.value || !selected.value) return
  const id = props.ruleId
  const revision = selected.value.revision
  const expected = current.value?.revisionToken
  const absent = current.value === null
  const message = t('detect.restoreConfirm', { revision })
    + (selected.value.status?.toUpperCase() === 'ACTIVE' ? ` ${t('detect.restoreActiveWarning')}` : '')
    + (props.hasDraft ? ` ${t('detect.restoreDraftWarning')}` : '')
  if (!await confirmDanger(message)) return
  if (!canRestoreSelection.value || !props.modelValue || props.ruleId !== id || selected.value?.revision !== revision || current.value?.revisionToken !== expected) return
  restoring.value = true
  emit('busy', true)
  actionError.value = ''
  restoredMessage.value = ''
  try {
    const restored = await restoreRuleRevision(id, revision, expected, absent)
    current.value = restored
    emit('restored', restored)
    restoredMessage.value = t('detect.restoreSucceeded', { revision })
    page.value = 1
    await load()
  } catch (error) {
    actionError.value = error instanceof ApiError && [412, 428].includes(error.status)
      ? t('detect.historyConflict') : error instanceof Error ? error.message : String(error)
  } finally { restoring.value = false; emit('busy', false) }
}

async function changePage(next: number) { page.value = next; await load() }
watch(() => [props.modelValue, props.ruleId], () => {
  reads.cancel(); details.cancel()
  if (!props.modelValue) return
  page.value = 1; rows.value = []; selected.value = null; current.value = null
  currentKnown.value = false; detailError.value = ''; detailLoading.value = false; actionError.value = ''; restoredMessage.value = ''
  void load()
}, { immediate: true })
</script>

<template>
  <el-drawer v-model="open" :title="t('detect.revisionHistory')" size="min(1100px, 96vw)" :before-close="close" :close-on-click-modal="!restoring" :close-on-press-escape="!restoring">
    <section class="rule-history" :aria-busy="loading || restoring">
      <p>{{ ruleId }}</p><p class="form-hint">{{ t('detect.historyHint') }}</p>
      <el-button :disabled="loading || restoring" @click="load">{{ t('common.refresh') }}</el-button>
      <p v-if="loading" role="status">{{ t('common.loading') }}</p>
      <p v-if="loadError" role="alert">{{ loadError }}</p>
      <p v-if="!loading && !loadError && !rows.length">{{ t('detect.noHistory') }}</p>
      <div class="history-table-scroll">
        <table v-if="rows.length" class="history-table">
          <caption>{{ t('common.total', { total }) }}</caption>
          <thead><tr><th>{{ t('detect.revision') }}</th><th>{{ t('detect.historyChange') }}</th><th>{{ t('detect.historyActor') }}</th><th>{{ t('detect.historyTime') }}</th><th>{{ t('common.actions') }}</th></tr></thead>
          <tbody><tr v-for="row in rows" :key="row.revision" :class="{ selected: selected?.revision === row.revision }"><td>#{{ row.revision }}</td><td>{{ sourceLabel(row.source) }}</td><td>{{ row.changedBy || '—' }}</td><td>{{ row.changedAt ? d(row.changedAt) : '—' }}</td><td><el-button :disabled="restoring" @click="selectRevision(row.revision)">{{ t('detect.compareRevision') }}</el-button></td></tr></tbody>
        </table>
      </div>
      <nav v-if="totalPages > 1" class="history-pagination" :aria-label="t('detect.revisionHistory')"><el-button :disabled="loading || restoring || page <= 1" @click="changePage(page - 1)">{{ t('detect.previousHistoryPage') }}</el-button><span>{{ page }} / {{ totalPages }}</span><el-button :disabled="loading || restoring || page >= totalPages" @click="changePage(page + 1)">{{ t('detect.nextHistoryPage') }}</el-button></nav>
      <p v-if="detailLoading" role="status">{{ t('common.loading') }}</p><p v-if="detailError" role="alert">{{ detailError }}</p>
      <div v-if="selected && currentKnown" class="history-comparison">
        <section><h3>{{ t('detect.currentRule') }}</h3><pre v-if="current" tabindex="0">{{ display(current) }}</pre><p v-else>{{ t('detect.currentRuleDeleted') }}</p></section>
        <section><h3>{{ t('detect.selectedRevision', { revision: selected.revision }) }}</h3><pre tabindex="0">{{ display(selected.spec) }}</pre></section>
      </div>
      <p v-if="actionError" role="alert">{{ actionError }}</p><p v-if="restoredMessage" role="status">{{ restoredMessage }}</p>
      <p v-if="restoring" role="status">{{ t('common.busySaving') }}</p>
      <el-button v-if="canRestore" type="danger" :disabled="!canRestoreSelection" :loading="restoring" @click="restore">{{ t('detect.restoreRevision') }}</el-button>
      <p v-else class="form-hint">{{ t('detect.restoreAdminOnly') }}</p>
    </section>
  </el-drawer>
</template>

<style scoped>
.rule-history { display: grid; gap: 12px; }
.history-table-scroll { overflow-x: auto; }
.history-table { width: 100%; border-collapse: collapse; text-align: left; }
.history-table caption { text-align: left; padding: 8px 0; }
.history-table th, .history-table td { padding: 8px; border-bottom: 1px solid var(--ns-border); }
.history-table .selected { background: var(--ns-surface-muted); }
.history-pagination { display: flex; gap: 12px; align-items: center; }
.history-comparison { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.history-comparison pre { max-height: 400px; overflow: auto; padding: 12px; border: 1px solid var(--ns-border); background: var(--ns-bg-inset); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
@media (max-width: 760px) { .history-comparison { grid-template-columns: 1fr; } }
</style>
