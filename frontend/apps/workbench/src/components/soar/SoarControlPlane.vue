<script setup lang="ts">
import { useFormDialog } from '../../composables/useFormDialog'
import ElSwitch from 'element-plus/es/components/switch/index.mjs'
import ElInputNumber from 'element-plus/es/components/input-number/index.mjs'
import 'element-plus/es/components/switch/style/css.mjs'
import 'element-plus/es/components/input-number/style/css.mjs'
import ElDialog from 'element-plus/es/components/dialog/index.mjs'
import ElDrawer from 'element-plus/es/components/drawer/index.mjs'
import ElForm from 'element-plus/es/components/form/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import 'element-plus/es/components/dialog/style/css.mjs'
import 'element-plus/es/components/drawer/style/css.mjs'
import 'element-plus/es/components/form/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import SchemaInputForm from '../SchemaInputForm.vue'
import SoarCatalogPager from './SoarCatalogPager.vue'
const showRuleTest = ref(false)
const editingRule = ref<SoarAutomationRule | null>(null)
const selectedTask = ref<SoarManualTask | null>(null)
const taskValue = ref<Record<string, unknown>>({})
const taskValid = ref(true)

import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/card/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElCard from 'element-plus/es/components/card/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import FieldConditionBuilder from '../FieldConditionBuilder.vue'
import FormField from '../FormField.vue'
import FormGrid from '../FormGrid.vue'
import FormSection from '../FormSection.vue'
import { useI18n } from '../../composables/useI18n'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useConfirm } from '../../composables/useConfirm'
import { tOr } from '../../utils/i18nLabel'
import {
  createAutomationRule,
  createConnection as createConnectionApi,
  discardDeadDispatch,
  deleteConnection,
  getStats,
  listActions,
  listAutomationRules,
  listConnections,
  listDeadDispatches,
  listManualTasksPage,
  listPlaybooks,
  listVersions,
  patchAutomationRule,
  requeueDeadDispatch,
  setAutomationRuleEnabled,
  setConnectionEnabled,
  testAutomationRules,
  testConnection as testConnectionApi,
  completeManualTask,
  type SoarActionDescriptor,
  type SoarAutomationRule,
  type SoarConnection,
  type SoarDeadLetter,
  type SoarManualTask,
  type SoarPlaybook,
  type SoarStats,
} from '../../api'
import type { FieldDef, RuleCondition, SoarPage } from '../../api'

export type SoarControlPlaneSection = 'rules' | 'tasks' | 'connections-and-ops'
type Tab = 'rules' | 'connections' | 'tasks' | 'operations'
type CatalogTab = Exclude<Tab, 'operations'>
const catalogPageSize = 25
const catalogPages = reactive({
  rules: { page: 0, total: 0, totalPages: 0 },
  connections: { page: 0, total: 0, totalPages: 0 },
  tasks: { page: 0, total: 0, totalPages: 0 },
})

const props = withDefaults(defineProps<{
  initialVersionId?: string
  hideTabs?: boolean
  section: SoarControlPlaneSection
  canWrite?: boolean
  /** Fine-grained capabilities mirror the controller guards. */
  canPublish?: boolean
  canViewConnections?: boolean
  canManageConnections?: boolean
  canOperate?: boolean
  canCompleteTasks?: boolean
}>(), {
  hideTabs: false,
  canWrite: true,
  canPublish: true,
  canViewConnections: true,
  canManageConnections: true,
  canOperate: true,
  canCompleteTasks: true,
})

const tab = ref<Tab>(props.section === 'connections-and-ops' ? 'connections' : props.section)

watch(() => props.section, (val) => {
  if (val !== 'connections-and-ops') {
    tab.value = val
  } else if (tab.value !== 'connections' && tab.value !== 'operations') {
    tab.value = 'connections'
  }
})
let loadController: AbortController | null = null
let disposed = false
// A row error belongs to the table that raised it. Each tab also owns its
// data load so a hidden control-plane section does not fan out six requests.
watch(tab, () => {
  rowError.value = ''
  void load()
})
const loading = ref(false)
const message = ref('')
/** Card-level banner: the shared catalog load failed, so a list may be stale. */
const loadError = ref('')
/** A row-scoped write failed (enable, test, delete, requeue, discard). */
const rowError = ref('')
/** The form currently open in a drawer or dialog rejected its own submit. */
const formError = ref('')
const rules = ref<SoarAutomationRule[]>([])
const connections = ref<SoarConnection[]>([])
const actions = ref<SoarActionDescriptor[]>([])
const tasks = ref<SoarManualTask[]>([])
const deadLetters = ref<SoarDeadLetter[]>([])
const stats = ref<SoarStats | null>(null)
const showRuleForm = ref(false)
const showConnectionForm = ref(false)
const ruleEventText = ref('{\n  "eventId": "sample-alert-1",\n  "type": "alert.created",\n  "severity": "HIGH"\n}')
const ruleTestResult = ref<Record<string, unknown>[] | null>(null)
const ruleConditionRows = ref<RuleCondition[]>([])
const automationFields: FieldDef[] = [
  { id: 'automation-event-type', fieldName: 'type', fieldLabel: 'Event type', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'alert.created, alert.enriched, case.updated, or another event type' },
  { id: 'automation-severity', fieldName: 'severity', fieldLabel: 'Severity', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'INFO through CRITICAL' },
  { id: 'automation-source', fieldName: 'source', fieldLabel: 'Source', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'Event source or collector' },
  { id: 'automation-host', fieldName: 'host', fieldLabel: 'Host', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'Host associated with the event' },
  { id: 'automation-entity', fieldName: 'entity', fieldLabel: 'Entity', fieldType: 'string', source: 'automation event', searchable: true, aggregatable: false, stored: true, description: 'User, host, IP, or other entity' },
]
const rulePlaybooks = ref<SoarPlaybook[]>([])
const rulePlaybookPage = reactive({ page: 0, total: 0, totalPages: 0 })
const rulePlaybooksLoading = ref(false)
const rulePlaybooksError = ref('')
const selectedRulePlaybook = ref<SoarPlaybook | null>(null)
const rulePlaybookOptions = computed(() => selectedRulePlaybook.value && !rulePlaybooks.value.some(item => item.id === selectedRulePlaybook.value?.id)
  ? [selectedRulePlaybook.value, ...rulePlaybooks.value] : rulePlaybooks.value)
const currentVersionOptions = ref<Array<{ id: string; label: string }>>([])
// Only retain metadata for selected versions, rather than accumulating every
// playbook visited. Existing rule IDs remain visible without a catalog scan.
const selectedVersionLabels = ref<Record<string, string>>({})
const publishedVersionOptions = computed(() => [
  ...ruleForm.playbookVersionIds.filter(id => !currentVersionOptions.value.some(version => version.id === id))
    .map(id => ({ id, label: selectedVersionLabels.value[id] || id })),
  ...currentVersionOptions.value,
])
let rulePlaybooksController: AbortController | null = null
let versionsController: AbortController | null = null
const versionOptionsLoading = ref(false)
const versionOptionsError = ref('')
/** A new rule starts on the engine's recommended suppression window, not an empty object. */
const defaultSuppressionJson = JSON.stringify({ dedupWindowSeconds: 300, conflictStrategy: 'QUEUE' }, null, 2)
const ruleForm = reactive({
  name: '', triggerType: 'alert.created', priority: 100, playbookVersionIds: [] as string[],
  conditions: '{}', suppression: defaultSuppressionJson,
})
watch([() => ruleForm.playbookVersionIds, currentVersionOptions], () => {
  selectedVersionLabels.value = Object.fromEntries(ruleForm.playbookVersionIds.map(id => [id,
    currentVersionOptions.value.find(version => version.id === id)?.label || selectedVersionLabels.value[id] || id,
  ]))
}, { deep: true, flush: 'sync' })
const connectionForm = reactive({
  name: '', connectorType: 'http.webhook', endpoint: '', authSecretRef: '', allowedHosts: '', enabled: true,
})
/** Field-level connection errors: a banner alone does not say which box is wrong. */
const connectionFieldErrors = reactive({ name: '', endpoint: '', allowedHosts: '' })
/** Per-row in-flight action, so one slow write never greys out the whole table. */
const ruleAction = ref<Record<string, 'toggle' | ''>>({})
const connectionAction = ref<Record<string, 'test' | 'enable' | 'delete' | ''>>({})
const deadLetterAction = ref<Record<string, 'requeue' | 'discard' | ''>>({})
const ruleFormBusy = ref(false)
const connectionFormBusy = ref(false)
const ruleTestBusy = ref(false)
const taskBusy = ref(false)
const taskOpen = ref(false)
const taskGuard = useFormDialog(taskOpen, () => taskValue.value, () => taskBusy.value)
const ruleGuard = useFormDialog(showRuleForm, () => ruleForm, () => ruleFormBusy.value)
const connectionGuard = useFormDialog(showConnectionForm, () => connectionForm, () => connectionFormBusy.value)
const connectorTypeOptions = computed(() => Array.from(new Set([
  connectionForm.connectorType,
  ...actions.value.map(action => action.connectorId),
].filter(Boolean))))

const suppressionSettings = computed<Record<string, unknown>>(() => {
  try { return parseJson(ruleForm.suppression) as Record<string, unknown> } catch { return {} }
})
function updateSuppression(key: string, value: unknown) {
  if (!props.canWrite) return
  try { ruleForm.suppression = JSON.stringify({ ...parseJson(ruleForm.suppression) as Record<string, unknown>, [key]: value }, null, 2) }
  catch (failure) { formError.value = failureText(failure) }
}

function clearFeedback() { message.value = ''; loadError.value = ''; rowError.value = ''; formError.value = '' }
function failureText(failure: unknown) { return failure instanceof Error ? failure.message : t('soar.requestFailed') }
function parseJson(value: string, fallback: unknown = {}) {
  try { return value.trim() ? JSON.parse(value) : fallback } catch { throw new Error(t('soar.invalidJsonPayload')) }
}

/** A deletion may move the last page backwards; never walk or preload a catalog. */
async function readCatalogPage<T>(fetchPage: (page: number) => Promise<SoarPage<T>>, requestedPage: number, isCurrent: () => boolean) {
  let page = requestedPage
  let result = await fetchPage(page)
  while (isCurrent()) {
    const lastPage = Math.max(0, (result.totalPages ?? Math.ceil(result.total / catalogPageSize)) - 1)
    if (page <= lastPage) return result
    page = lastPage
    result = await fetchPage(page)
  }
  return null
}

function pageMetadata(page: SoarPage<unknown>) {
  return { page: page.page, total: page.total, totalPages: page.totalPages ?? Math.ceil(page.total / catalogPageSize) }
}

function changeCatalogPage(catalog: CatalogTab, page: number) {
  if (disposed || tab.value !== catalog || page < 0 || page >= catalogPages[catalog].totalPages || page === catalogPages[catalog].page) return
  catalogPages[catalog].page = page
  if (catalog === 'rules') rules.value = []
  if (catalog === 'connections') connections.value = []
  if (catalog === 'tasks') tasks.value = []
  void load(true)
}

async function load(catalogOnly = false) {
  if (disposed) return
  loadController?.abort()
  const controller = new AbortController()
  loadController = controller
  const options = { signal: controller.signal }
  const isCurrent = () => !disposed && !controller.signal.aborted && loadController === controller
  loading.value = true
  clearFeedback()
  const showRules = tab.value === 'rules'
  const showConnections = tab.value === 'connections'
  const showTasks = tab.value === 'tasks'
  const showOperations = tab.value === 'operations'
  const results = await Promise.allSettled([
    showRules ? readCatalogPage(page => listAutomationRules(page, catalogPageSize, options), catalogPages.rules.page, isCurrent) : Promise.resolve(null),
    showConnections && props.canViewConnections ? readCatalogPage(page => listConnections(page, catalogPageSize, options), catalogPages.connections.page, isCurrent) : Promise.resolve(null),
    showConnections && !catalogOnly ? listActions(options) : Promise.resolve(null),
    showTasks ? readCatalogPage(page => listManualTasksPage(true, page, catalogPageSize, options), catalogPages.tasks.page, isCurrent) : Promise.resolve(null),
    showOperations && props.canOperate ? listDeadDispatches(options) : Promise.resolve(null),
    showOperations ? getStats(options) : Promise.resolve(null),
  ])
  if (!isCurrent()) return
  const [ruleResult, connectionResult, actionResult, taskResult, deadResult, statsResult] = results
  if (ruleResult.status === 'fulfilled' && ruleResult.value) {
    rules.value = ruleResult.value.items
    Object.assign(catalogPages.rules, pageMetadata(ruleResult.value))
  }
  if (connectionResult.status === 'fulfilled' && connectionResult.value) {
    connections.value = connectionResult.value.items
    Object.assign(catalogPages.connections, pageMetadata(connectionResult.value))
  }
  if (showConnections && !props.canViewConnections) {
    connections.value = []
    Object.assign(catalogPages.connections, { page: 0, total: 0, totalPages: 0 })
  }
  if (actionResult.status === 'fulfilled' && actionResult.value) actions.value = actionResult.value
  if (taskResult.status === 'fulfilled' && taskResult.value) {
    tasks.value = taskResult.value.items
    Object.assign(catalogPages.tasks, pageMetadata(taskResult.value))
  }
  if (deadResult.status === 'fulfilled' && deadResult.value) deadLetters.value = deadResult.value
  if (showOperations && !props.canOperate) deadLetters.value = []
  if (statsResult.status === 'fulfilled' && statsResult.value) stats.value = statsResult.value
  const failures = results.filter(item => item.status === 'rejected')
  if (failures.length) loadError.value = failureText(failures[0].reason)
  loading.value = false
  loadController = null
}

function stopRuleCatalogRequests() {
  rulePlaybooksController?.abort()
  versionsController?.abort()
  rulePlaybooksController = null
  versionsController = null
  rulePlaybooksLoading.value = false
  versionOptionsLoading.value = false
}

watch(showRuleForm, visible => { if (!visible) stopRuleCatalogRequests() }, { flush: 'sync' })
watch([() => props.canWrite, tab], ([canWrite, section]) => {
  if (!canWrite || section !== 'rules') {
    showRuleForm.value = false
    stopRuleCatalogRequests()
  }
}, { flush: 'sync' })

function openRuleCatalog() {
  stopRuleCatalogRequests()
  rulePlaybooks.value = []
  Object.assign(rulePlaybookPage, { page: 0, total: 0, totalPages: 0 })
  selectedRulePlaybook.value = null
  currentVersionOptions.value = []
  selectedVersionLabels.value = {}
  versionOptionsError.value = ''
  void loadRulePlaybooks(0)
}

async function loadRulePlaybooks(page: number) {
  if (disposed || !props.canWrite || tab.value !== 'rules' || !showRuleForm.value) return
  rulePlaybooksController?.abort()
  const controller = new AbortController()
  rulePlaybooksController = controller
  const isCurrent = () => !disposed && showRuleForm.value && !controller.signal.aborted && rulePlaybooksController === controller
  rulePlaybooksLoading.value = true
  rulePlaybooksError.value = ''
  rulePlaybooks.value = []
  rulePlaybookPage.page = page
  try {
    const result = await readCatalogPage(index => listPlaybooks(index, catalogPageSize, { signal: controller.signal }), page, isCurrent)
    if (!isCurrent() || !result) return
    rulePlaybooks.value = result.items
    Object.assign(rulePlaybookPage, pageMetadata(result))
  } catch (failure) {
    if (isCurrent()) rulePlaybooksError.value = failureText(failure)
  } finally {
    if (isCurrent()) {
      rulePlaybooksLoading.value = false
      rulePlaybooksController = null
    }
  }
}

async function selectRulePlaybook(id: string) {
  if (disposed || !props.canWrite || tab.value !== 'rules' || !showRuleForm.value) return
  const playbook = rulePlaybookOptions.value.find(item => item.id === id)
  if (!playbook) return
  versionsController?.abort()
  const controller = new AbortController()
  versionsController = controller
  const isCurrent = () => !disposed && showRuleForm.value && !controller.signal.aborted && versionsController === controller
  selectedRulePlaybook.value = playbook
  currentVersionOptions.value = []
  versionOptionsLoading.value = true
  versionOptionsError.value = ''
  try {
    const versions = await listVersions(id, { signal: controller.signal })
    if (!isCurrent()) return
    currentVersionOptions.value = versions.filter(version => version.status === 'PUBLISHED')
      .map(version => ({ id: version.id, label: `${playbook.name} · Revision ${version.version}` }))
  } catch (failure) {
    if (isCurrent()) versionOptionsError.value = failureText(failure)
  } finally {
    if (isCurrent()) {
      versionOptionsLoading.value = false
      versionsController = null
    }
  }
}

const conditionJsonError = ref('')
/** True while the builder itself writes the JSON textarea; that edit must not round-trip back. */
let builderAuthoredConditions = false

function syncRuleConditionRows(): void {
  let parsed: unknown
  try { parsed = parseJson(ruleForm.conditions, {}) }
  catch { conditionJsonError.value = t('soar.invalidJson', { label: t('soar.advancedConditionsJson') }); return }
  conditionJsonError.value = ''
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { ruleConditionRows.value = []; return }
  // Only non-string values are out of the builder's reach; dropping every row
  // because one key is advanced made the two views fight each other.
  ruleConditionRows.value = Object.entries(parsed as Record<string, unknown>)
    .filter(([, value]) => typeof value === 'string')
    .map(([field, value]) => ({ field, op: 'eq', value: String(value) }))
}

/** Advanced (non-string) keys the JSON textarea keeps across builder commits. */
const advancedConditionKeys = computed<string[]>(() => {
  try {
    const parsed = parseJson(ruleForm.conditions, {}) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return []
    return Object.entries(parsed as Record<string, unknown>)
      .filter(([, value]) => typeof value !== 'string')
      .map(([key]) => key)
  } catch { return [] }
})

// The textarea and the builder are two views of the same object: re-parse a hand
// edit into rows instead of letting the next builder commit silently overwrite it.
watch(() => ruleForm.conditions, () => {
  if (builderAuthoredConditions) return
  syncRuleConditionRows()
})

function commitRuleConditionRows(rows: RuleCondition[]): void {
  if (!props.canWrite) return
  let existing: unknown
  try { existing = parseJson(ruleForm.conditions, {}) }
  catch {
    conditionJsonError.value = t('soar.invalidJson', { label: t('soar.advancedConditionsJson') })
    return
  }
  const advanced = existing && typeof existing === 'object' && !Array.isArray(existing)
    ? Object.fromEntries(Object.entries(existing as Record<string, unknown>).filter(([, value]) => typeof value !== 'string'))
    : {}
  ruleConditionRows.value = rows.map(row => ({ ...row }))
  const conditions = Object.fromEntries(ruleConditionRows.value.filter(row => row.field.trim() && row.value.trim()).map(row => [row.field.trim(), row.value.trim()]))
  builderAuthoredConditions = true
  ruleForm.conditions = JSON.stringify({ ...advanced, ...conditions }, null, 2)
  void nextTick(() => { builderAuthoredConditions = false })
}

function openRuleForm(): void {
  if (!props.canWrite) return
  editingRule.value = null
  formError.value = ''
  conditionJsonError.value = ''
  Object.assign(ruleForm, { name: '', triggerType: 'alert.created', priority: 100, playbookVersionIds: [], conditions: '{}', suppression: defaultSuppressionJson })
  showRuleForm.value = true
  syncRuleConditionRows()
  openRuleCatalog()
}

function toggleRuleForm(): void {
  if (!props.canWrite) return
  if (showRuleForm.value) { void ruleGuard.cancel(); return }
  openRuleForm()
}

async function createRule() {
  if (!props.canWrite || ruleFormBusy.value) return
  ruleFormBusy.value = true
  formError.value = ''
  try {
    const ids = ruleForm.playbookVersionIds.map(value => value.trim()).filter(Boolean)
    if (!ruleForm.name.trim() || !ids.length) throw new Error(t('soar.ruleNameRequired'))
    const payload = {
      name: ruleForm.name.trim(), triggerType: ruleForm.triggerType.trim() || 'ANY',
      priority: Number(ruleForm.priority) || 0, enabled: false,
      conditions: parseJson(ruleForm.conditions), actions: ids.map(playbookVersionId => {
        const existing = (Array.isArray(editingRule.value?.actions) ? editingRule.value.actions : []).find((action: Record<string, unknown>) => action.playbookVersionId === playbookVersionId)
        return existing ? { ...existing, playbookVersionId } : { playbookVersionId }
      }),
      suppression: parseJson(ruleForm.suppression),
    }
    const updating = Boolean(editingRule.value)
    if (updating) await patchAutomationRule(editingRule.value!.id, { ...payload, enabled: editingRule.value!.enabled, rowVersion: editingRule.value!.rowVersion })
    else await createAutomationRule(payload)
    showRuleForm.value = false
    ruleForm.name = ''
    ruleForm.playbookVersionIds = []
    await load()
    message.value = updating ? t('common.updated') : t('soar.ruleCreatedDisabled')
  } catch (failure) { formError.value = failureText(failure) }
  finally { ruleFormBusy.value = false }
}

async function toggleRule(rule: SoarAutomationRule) {
  if (!props.canPublish || ruleAction.value[rule.id]) return
  const id = rule.id
  const enabled = !rule.enabled
  ruleAction.value = { ...ruleAction.value, [id]: 'toggle' }
  rowError.value = ''
  try {
    if (!(await confirmDanger(t(enabled ? 'soar.enableRuleConfirm' : 'soar.disableRuleConfirm', { name: rule.name }))) || !props.canPublish || disposed) return
    await setAutomationRuleEnabled(id, enabled)
    await load()
    message.value = enabled ? t('soar.ruleEnabled') : t('soar.ruleDisabled')
  } catch (failure) { rowError.value = failureText(failure) }
  finally { ruleAction.value = { ...ruleAction.value, [id]: '' } }
}

function editRule(rule: SoarAutomationRule) {
  if (!props.canWrite) return
  formError.value = ''
  conditionJsonError.value = ''
  editingRule.value = rule
  Object.assign(ruleForm, { name: rule.name, triggerType: rule.triggerType, priority: rule.priority,
    conditions: JSON.stringify(rule.conditions ?? {}, null, 2), suppression: JSON.stringify(rule.suppression ?? {}, null, 2),
    playbookVersionIds: Array.isArray(rule.actions) ? rule.actions.map(action => String((action as Record<string, unknown>).playbookVersionId || '')).filter(Boolean) : [],
  })
  syncRuleConditionRows()
  showRuleForm.value = true
  openRuleCatalog()
}
function openTask(task: SoarManualTask) {
  formError.value = ''
  selectedTask.value = task
  taskValue.value = task.input && typeof task.input === 'object' ? structuredClone(task.input) as Record<string, unknown> : {}
  taskValid.value = true
  taskOpen.value = true
}

function openRuleTest(): void {
  formError.value = ''
  showRuleTest.value = true
}

async function testRules() {
  if (!props.canWrite || ruleTestBusy.value) return
  ruleTestBusy.value = true
  formError.value = ''
  try {
    ruleTestResult.value = await testAutomationRules(parseJson(ruleEventText.value) as Record<string, unknown>)
  } catch (failure) { formError.value = failureText(failure) }
  finally { ruleTestBusy.value = false }
}

/** The SSRF guard only accepts a plain https origin, so preflight it here. */
function isHttpsEndpoint(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && Boolean(url.hostname) && !url.username && !url.password
  } catch { return false }
}

function validateConnectionForm(): boolean {
  const endpoint = connectionForm.endpoint.trim()
  const endpointHint = t('forms.serverPattern', { pattern: t('soar.endpointPlaceholder') })
  connectionFieldErrors.name = connectionForm.name.trim() ? '' : t('forms.fieldRequired', { field: t('common.name') })
  connectionFieldErrors.endpoint = !endpoint
    ? t('forms.fieldRequired', { field: t('soar.httpsEndpoint') })
    : (isHttpsEndpoint(endpoint) ? '' : endpointHint)
  connectionFieldErrors.allowedHosts = connectionForm.allowedHosts.split(/[,\n]/).some(value => value.trim())
    ? ''
    : t('forms.fieldRequired', { field: t('soar.allowedHosts') })
  return !connectionFieldErrors.name && !connectionFieldErrors.endpoint && !connectionFieldErrors.allowedHosts
}

watch(() => connectionForm.name, () => { connectionFieldErrors.name = '' })
watch(() => connectionForm.endpoint, () => { connectionFieldErrors.endpoint = '' })
watch(() => connectionForm.allowedHosts, () => { connectionFieldErrors.allowedHosts = '' })

function openConnectionForm(): void {
  if (!props.canWrite) return
  formError.value = ''
  connectionFieldErrors.name = ''
  connectionFieldErrors.endpoint = ''
  connectionFieldErrors.allowedHosts = ''
  showConnectionForm.value = true
}

function toggleConnectionForm(): void {
  if (!props.canWrite) return
  if (showConnectionForm.value) { void connectionGuard.cancel(); return }
  openConnectionForm()
}

async function createConnection() {
  if (!props.canManageConnections || connectionFormBusy.value) return
  connectionFormBusy.value = true
  formError.value = ''
  try {
    if (!validateConnectionForm()) { formError.value = t('forms.invalidInput'); return }
    const allowedHosts = connectionForm.allowedHosts.split(/[,\n]/).map(value => value.trim()).filter(Boolean)
    await createConnectionApi({ name: connectionForm.name.trim(), connectorType: connectionForm.connectorType.trim(),
      endpoint: connectionForm.endpoint.trim(), authSecretRef: connectionForm.authSecretRef.trim() || undefined,
      allowedHosts, enabled: connectionForm.enabled })
    showConnectionForm.value = false
    await load()
    message.value = t('soar.connectionCreated')
  } catch (failure) { formError.value = failureText(failure) }
  finally { connectionFormBusy.value = false }
}

async function toggleConnection(connection: SoarConnection) {
  if (!props.canManageConnections || connectionAction.value[connection.id]) return
  const id = connection.id
  const enabled = !connection.enabled
  connectionAction.value = { ...connectionAction.value, [id]: 'enable' }
  rowError.value = ''
  try {
    if (!(await confirmDanger(t(enabled ? 'soar.enableConnectionConfirm' : 'soar.disableConnectionConfirm', { name: connection.name }))) || !props.canManageConnections || disposed) return
    await setConnectionEnabled(id, enabled)
    await load()
    message.value = t('soar.connectionStateUpdated')
  } catch (failure) { rowError.value = failureText(failure) }
  finally { connectionAction.value = { ...connectionAction.value, [id]: '' } }
}

async function testConnection(connection: SoarConnection) {
  if (!props.canManageConnections || connectionAction.value[connection.id]) return
  connectionAction.value = { ...connectionAction.value, [connection.id]: 'test' }
  rowError.value = ''
  try {
    const result = await testConnectionApi(connection.id)
    await load()
    message.value = t('soar.connectionTestResult', { status: result.status })
  } catch (failure) { rowError.value = failureText(failure) }
  finally { connectionAction.value = { ...connectionAction.value, [connection.id]: '' } }
}

async function removeConnection(connection: SoarConnection) {
  if (!props.canManageConnections || connectionAction.value[connection.id]) return
  const id = connection.id
  connectionAction.value = { ...connectionAction.value, [id]: 'delete' }
  rowError.value = ''
  try {
    if (!(await confirmDanger(t('soar.deleteConnectionConfirm', { name: connection.name }))) || !props.canManageConnections || disposed) return
    await deleteConnection(id)
    await load()
    message.value = t('soar.connectionRemoved')
  } catch (failure) { rowError.value = failureText(failure) }
  finally { connectionAction.value = { ...connectionAction.value, [id]: '' } }
}

async function completeTask(task: SoarManualTask) {
  if (!props.canCompleteTasks || taskBusy.value) return
  taskBusy.value = true
  formError.value = ''
  try {
    if (!taskValid.value) throw new Error(t('forms.invalidInput'))
    const schema = task.formSchema as { required?: string[] } | null
    const missing = (schema?.required ?? []).filter(key => taskValue.value[key] === undefined || taskValue.value[key] === '')
    if (missing.length) throw new Error(t('soar.manualRequired', { fields: missing.join(', ') }))
    const value = taskValue.value
    if (typeof value !== 'object' || Array.isArray(value) || value === null) throw new Error(t('soar.manualInputObject'))
    await completeManualTask(task.id, value as Record<string, unknown>)
    taskOpen.value = false
    selectedTask.value = null
    await load()
    message.value = t('forms.saved')
  } catch (failure) { formError.value = failureText(failure) }
  finally { taskBusy.value = false }
}

/** Stable row identity for the dead-letter table (kind + id, as rendered). */
function deadLetterKey(letter: SoarDeadLetter): string { return `${letter.kind}-${letter.id}` }

async function requeue(letter: SoarDeadLetter) {
  if (!props.canOperate || deadLetterAction.value[deadLetterKey(letter)]) return
  const key = deadLetterKey(letter)
  const id = letter.id
  deadLetterAction.value = { ...deadLetterAction.value, [key]: 'requeue' }
  rowError.value = ''
  try {
    if (!(await confirmDanger(t('soar.requeueDeadLetterConfirm'))) || !props.canOperate || disposed) return
    await requeueDeadDispatch(id, t('soar.requeueReason'))
    await load()
    message.value = t('soar.deadLetterRequeued')
  } catch (failure) { rowError.value = failureText(failure) }
  finally { deadLetterAction.value = { ...deadLetterAction.value, [key]: '' } }
}

async function discard(letter: SoarDeadLetter) {
  if (!props.canOperate || deadLetterAction.value[deadLetterKey(letter)]) return
  const key = deadLetterKey(letter)
  const id = letter.id
  deadLetterAction.value = { ...deadLetterAction.value, [key]: 'discard' }
  rowError.value = ''
  try {
    if (!(await confirmDanger(t('soar.discardDeadLetterConfirm'))) || !props.canOperate || disposed) return
    await discardDeadDispatch(id, t('soar.discardReason'))
    await load()
    message.value = t('soar.deadLetterDiscarded')
  } catch (failure) { rowError.value = failureText(failure) }
  finally { deadLetterAction.value = { ...deadLetterAction.value, [key]: '' } }
}

const { t } = useI18n()
const { confirmDanger } = useConfirm()
onMounted(() => {
  void load()
  if (props.section === 'rules' && props.canWrite && props.initialVersionId) {
    openRuleForm()
    ruleForm.playbookVersionIds = [props.initialVersionId]
    // The service still verifies the version is published when this reviewed form is saved.
  }
})
onUnmounted(() => { disposed = true; loadController?.abort(); stopRuleCatalogRequests() })

function statusLabel(status: string): string {
  return tOr(t, 'soar.status.' + status, status)
}

function controlTitle(): string {
  if (props.section === 'rules') return t('soar.controlRules')
  if (props.section === 'tasks') return t('soar.controlTasks')
  if (props.section === 'connections-and-ops') return t('soar.controlConnections')
  return t('soar.controlPlane')
}

function controlSubtitle(): string {
  if (props.section === 'rules') return t('soar.subtitleRules')
  if (props.section === 'tasks') return t('soar.subtitleTasks')
  if (props.section === 'connections-and-ops') return t('soar.subtitleConnections')
  return t('soar.subtitleAll')
}
</script>

<template>
  <el-card shadow="never" class="soar-control-plane">
    <template #header>
      <div class="soar-control-header">
        <div>
          <strong>{{ controlTitle() }}</strong>
          <span class="soar-subtitle">{{ controlSubtitle() }}</span>
        </div>
        <el-button size="small" :loading="loading" @click="load()">{{ t('common.refresh') }}</el-button>
      </div>
    </template>

    <div v-if="!hideTabs && section === 'connections-and-ops'" class="soar-tabs" role="tablist" :aria-label="t('soar.controlConnections')">
      <button v-for="item in (['connections', 'operations'] as Tab[])" :key="item" type="button" :class="{ active: tab === item }" role="tab" :aria-selected="tab === item" @click="tab = item">
        {{ item === 'connections' ? t('soar.connectionsCatalog') : t('soar.deadLetterOperations') }}
      </button>
    </div>
    <div v-if="message" class="soar-feedback success">{{ message }}</div>
    <div v-if="loadError" class="soar-feedback error">{{ loadError }}</div>

    <section v-if="tab === 'rules'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.eventRouting') }}</b><small>{{ t('soar.eventRoutingHint') }}</small></div><div><el-button v-if="props.canWrite" size="small" @click="toggleRuleForm">{{ showRuleForm ? t('soar.closeForm') : t('soar.newRule') }}</el-button><el-button v-if="props.canWrite" size="small" @click="openRuleTest">{{ t('forms.test') }}</el-button></div></div>
      <div v-if="rowError" role="alert" class="soar-feedback error">{{ rowError }}</div>
      <el-drawer v-if="props.canWrite" v-model="showRuleForm" :before-close="ruleGuard.beforeClose" :title="editingRule ? t('forms.edit') : t('detect.createRule')" size="min(760px, 96vw)" :close-on-click-modal="false"><div v-if="formError" role="alert" class="soar-feedback error">{{ formError }}</div><el-form label-position="top" :disabled="ruleFormBusy">
        <FormSection index="01" :title="t('soar.ruleBasics')" :hint="t('soar.ruleBasicsHint')">
          <FormGrid :columns="2">
            <FormField :label="t('common.name')" required>
              <el-input v-model="ruleForm.name" :placeholder="t('soar.namePlaceholder')" />
            </FormField>
            <FormField :label="t('soar.triggerType')" :hint="t('soar.triggerTypeHint')">
              <el-select v-model="ruleForm.triggerType" filterable default-first-option placeholder="alert.created">
                <el-option label="alert.created" value="alert.created" />
                <el-option label="alert.enriched" value="alert.enriched" />
                <el-option label="case.updated" value="case.updated" />
                <el-option label="ANY" value="ANY" />
              </el-select>
            </FormField>
            <FormField :label="t('soar.priority')" :hint="t('soar.priorityHint')">
              <el-input-number v-model="ruleForm.priority" :min="0" :max="10000" />
            </FormField>
            <FormField :label="t('soar.playbook')" :error="rulePlaybooksError" full>
              <el-select class="soar-rule-playbook-select" :model-value="selectedRulePlaybook?.id" filterable :loading="rulePlaybooksLoading" :placeholder="t('soar.playbook')" :aria-label="t('soar.playbook')" @change="selectRulePlaybook">
                <el-option v-for="playbook in rulePlaybookOptions" :key="playbook.id" :label="playbook.name" :value="playbook.id" />
              </el-select>
              <SoarCatalogPager v-bind="rulePlaybookPage" :loading="rulePlaybooksLoading" :disabled="ruleFormBusy" :label="t('soar.playbooks')" @change="loadRulePlaybooks" />
              <el-button v-if="rulePlaybooksError" size="small" @click="loadRulePlaybooks(rulePlaybookPage.page)">{{ t('common.refresh') }}</el-button>
            </FormField>
            <FormField :label="t('soar.publishedVersions')" :error="versionOptionsError" full>
              <el-select class="soar-rule-version-select" v-model="ruleForm.playbookVersionIds" multiple filterable default-first-option collapse-tags :loading="versionOptionsLoading" :placeholder="t('soar.publishedVersionsPlaceholder')">
                <el-option v-for="version in publishedVersionOptions" :key="version.id" :label="version.label" :value="version.id"><div class="soar-version-option"><b>{{ version.label }}</b><small>{{ version.id }}</small></div></el-option>
              </el-select>
              <el-button v-if="versionOptionsError && selectedRulePlaybook" size="small" @click="selectRulePlaybook(selectedRulePlaybook.id)">{{ t('common.refresh') }}</el-button>
            </FormField>
          </FormGrid>
        </FormSection>
        <FormSection index="02" :title="t('soar.matchConditions')" :hint="t('soar.matchConditionsHint')">
        <div class="soar-condition-builder">
          <FieldConditionBuilder :model-value="ruleConditionRows" :read-only="!props.canWrite" :fields="automationFields" :title="t('soar.simpleConditions')" :add-label="t('common.add')" :empty-hint="t('soar.simpleConditionsHint')" :field-placeholder="t('search.fieldSearchPlaceholder')" :value-placeholder="t('common.value')" @update:model-value="commitRuleConditionRows" />
          <div class="soar-condition-json">
            <details class="soar-inline-details"><summary>{{ t('soar.advancedConditionsJson') }}</summary><el-input type="textarea" v-model="ruleForm.conditions" :readonly="!props.canWrite" :rows="3" spellcheck="false"  /></details>
            <p v-if="conditionJsonError" role="alert" class="soar-field-error">{{ conditionJsonError }}</p>
            <p v-else-if="advancedConditionKeys.length" class="soar-field-warning">{{ t('forms.advancedHint') }}</p>
          </div>
        </div>
        </FormSection>
        <FormSection index="03" :title="t('soar.suppressionSettings')" :hint="t('soar.suppressionSettingsHint')">
          <FormGrid :columns="2">
            <FormField :label="t('forms.dedupWindow')">
              <el-input-number :model-value="suppressionSettings.dedupWindowSeconds as number | undefined" :min="0" :disabled="!props.canWrite" @change="value => updateSuppression('dedupWindowSeconds', value)" />
            </FormField>
            <FormField :label="t('forms.conflictStrategy')" :hint="t('soar.conflictStrategyHint')">
              <el-select :model-value="suppressionSettings.conflictStrategy as string | undefined" :disabled="!props.canWrite" @change="value => updateSuppression('conflictStrategy', value)"><el-option v-for="strategy in ['QUEUE', 'SUPPRESS']" :key="strategy" :value="strategy" :label="strategy" /></el-select>
            </FormField>
            <details><summary>{{ t('forms.advanced') }}</summary><el-input type="textarea" v-model="ruleForm.suppression" :readonly="!props.canWrite" :rows="3" spellcheck="false" /></details>
          </FormGrid>
        </FormSection></el-form>
        <template #footer>
          <el-button @click="ruleGuard.cancel">{{ t('common.cancel') }}</el-button>
          <el-button v-if="props.canWrite" type="primary" :loading="ruleFormBusy" @click="createRule">{{ t('common.save') }}</el-button>
        </template>
      </el-drawer>
      <el-dialog v-if="props.canWrite" v-model="showRuleTest" :title="t('forms.test')" width="720px"><div v-if="formError" role="alert" class="soar-feedback error">{{ formError }}</div><div class="soar-test-box"><el-input type="textarea" v-model="ruleEventText" :rows="3" spellcheck="false" :aria-label="t('soar.actionTestEvent')"  /><pre v-if="ruleTestResult">{{ JSON.stringify(ruleTestResult, null, 2) }}</pre></div><template #footer><el-button :loading="ruleTestBusy" @click="testRules">{{ t('forms.test') }}</el-button></template></el-dialog>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.name') }}</th><th>{{ t('soar.triggerType') }}</th><th>{{ t('soar.priority') }}</th><th>{{ t('soar.revision') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.publishedVersions') }}</th><th v-if="props.canWrite || props.canPublish">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="rule in rules" :key="rule.id"><td><b>{{ rule.name }}</b><small>{{ rule.id }}</small></td><td>{{ rule.triggerType }}</td><td>{{ rule.priority }}</td><td>{{ rule.revision || 1 }}</td><td><el-tag size="small" :type="rule.enabled ? 'success' : 'info'">{{ statusLabel(rule.enabled ? 'ENABLED' : 'DISABLED') }}</el-tag></td><td class="mono">{{ JSON.stringify(rule.actions) }}</td><td v-if="props.canWrite || props.canPublish" class="nowrap"><el-button v-if="props.canPublish" link size="small" :loading="ruleAction[rule.id] === 'toggle'" :disabled="Boolean(ruleAction[rule.id])" @click="toggleRule(rule)">{{ rule.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button v-if="props.canWrite" link size="small" @click="editRule(rule)">{{ t('common.edit') }}</el-button></td></tr></tbody></table><div v-if="!loading && !rules.length" class="soar-empty">{{ t('soar.noAutomationRules') }}</div></div>
      <SoarCatalogPager v-bind="catalogPages.rules" :loading="loading" :label="controlTitle()" @change="page => changeCatalogPage('rules', page)" />
    </section>

    <section v-else-if="tab === 'connections'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.connectorAssets') }}</b><small>{{ t('soar.connectorAssetsHint') }}</small></div><div><el-button v-if="props.canWrite" size="small" @click="toggleConnectionForm">{{ showConnectionForm ? t('soar.closeForm') : t('soar.newConnection') }}</el-button><details class="soar-inline-details"><summary>{{ t('soar.actionCatalog', { count: actions.length }) }}</summary><div class="soar-action-catalog"><span v-for="action in actions" :key="action.actionRef"><b>{{ action.actionRef }}</b><small>{{ action.riskLevel }} · {{ action.idempotency }} · {{ action.production ? t('soar.production') : t('soar.certificationRequired') }}</small></span></div></details></div></div>
      <div v-if="rowError" role="alert" class="soar-feedback error">{{ rowError }}</div>
      <el-dialog v-if="props.canWrite" v-model="showConnectionForm" :before-close="connectionGuard.beforeClose" :title="t('soar.tabConnections')" width="640px" :close-on-click-modal="false"><div v-if="formError" role="alert" class="soar-feedback error">{{ formError }}</div><el-form label-position="top" :disabled="connectionFormBusy"><FormGrid :columns="2">
        <FormField :label="t('common.name')" required :error="connectionFieldErrors.name"><el-input v-model="connectionForm.name" :placeholder="t('soar.connectionNamePlaceholder')" /></FormField>
        <FormField :label="t('soar.connectorType')">
          <el-select v-model="connectionForm.connectorType" filterable default-first-option :placeholder="t('soar.selectConnector')">
            <el-option v-for="connectorId in connectorTypeOptions" :key="connectorId" :label="connectorId" :value="connectorId" />
          </el-select>
        </FormField>
        <FormField :label="t('soar.httpsEndpoint')" required :error="connectionFieldErrors.endpoint" full><el-input v-model="connectionForm.endpoint" :placeholder="t('soar.endpointPlaceholder')" /></FormField>
        <FormField :label="t('soar.secretRef')" :hint="t('soar.secretRefHint')"><el-input v-model="connectionForm.authSecretRef" :placeholder="t('soar.secretRefPlaceholder')" /></FormField>
        <FormField :label="t('soar.allowedHosts')" required :hint="t('soar.allowedHostsHint')" :error="connectionFieldErrors.allowedHosts"><el-input v-model="connectionForm.allowedHosts" :placeholder="t('soar.allowedHostsPlaceholder')" /></FormField>
        <FormField :label="t('soar.enabledAfterCreate')"><el-switch v-model="connectionForm.enabled" /></FormField>
      </FormGrid></el-form>
        <template #footer>
          <el-button @click="connectionGuard.cancel">{{ t('common.cancel') }}</el-button>
          <el-button v-if="props.canWrite" type="primary" :loading="connectionFormBusy" @click="createConnection">{{ t('common.create') }}</el-button>
        </template>
      </el-dialog>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.name') }}</th><th>{{ t('common.type') }}</th><th>{{ t('soar.httpsEndpoint') }}</th><th>{{ t('common.status') }}</th><th>{{ t('soar.connectionTest') }}</th><th v-if="props.canWrite">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="connection in connections" :key="connection.id"><td><b>{{ connection.name }}</b><small>{{ connection.id }}</small></td><td>{{ connection.connectorType }}</td><td class="mono">{{ connection.endpoint }}</td><td><el-tag size="small" :type="connection.status === 'HEALTHY' ? 'success' : connection.enabled ? 'warning' : 'info'">{{ statusLabel(connection.status) }}</el-tag></td><td>{{ connection.lastTestAt || '-' }}<small>{{ connection.lastTestError || '' }}</small></td><td v-if="props.canWrite" class="nowrap"><el-button link size="small" :loading="connectionAction[connection.id] === 'test'" :disabled="Boolean(connectionAction[connection.id])" @click="testConnection(connection)">{{ t('soar.connectionTest') }}</el-button><el-button link size="small" :loading="connectionAction[connection.id] === 'enable'" :disabled="Boolean(connectionAction[connection.id])" @click="toggleConnection(connection)">{{ connection.enabled ? t('common.disable') : t('common.enable') }}</el-button><el-button link type="danger" size="small" :loading="connectionAction[connection.id] === 'delete'" :disabled="Boolean(connectionAction[connection.id])" @click="removeConnection(connection)">{{ t('common.delete') }}</el-button></td></tr></tbody></table><div v-if="!loading && !connections.length" class="soar-empty">{{ t('soar.noConnections') }}</div></div>
      <SoarCatalogPager v-bind="catalogPages.connections" :loading="loading" :label="controlTitle()" @change="page => changeCatalogPage('connections', page)" />
    </section>

    <section v-else-if="tab === 'tasks'" class="soar-control-section">
      <div class="soar-section-toolbar"><div><b>{{ t('soar.humanTasks') }}</b><small>{{ t('soar.humanTasksHint') }}</small></div></div>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('forms.task') }}</th><th>{{ t('soar.runNode') }}</th><th>{{ t('forms.assign') }}</th><th>{{ t('soar.due') }}</th><th>{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="task in tasks" :key="task.id"><td><b>{{ task.id }}</b><small>{{ statusLabel(task.status) }}</small></td><td class="mono">{{ task.runId }} / {{ task.nodeId }}</td><td>{{ task.assignee || t('soar.anyApprover') }}</td><td>{{ task.dueAt || '-' }}</td><td><el-button size="small" type="primary" plain @click="openTask(task)">{{ t('forms.task') }}</el-button></td></tr></tbody></table><div v-if="!loading && !tasks.length" class="soar-empty">{{ t('soar.noPendingTasks') }}</div></div>
      <SoarCatalogPager v-bind="catalogPages.tasks" :loading="loading" :label="controlTitle()" @change="page => changeCatalogPage('tasks', page)" />
    </section>

    <section v-else class="soar-control-section">
      <div class="soar-stat-grid"><div><b>{{ stats?.dispatchBacklog ?? 0 }}</b><small>{{ t('soar.dispatchBacklog') }}</small></div><div><b>{{ stats?.signalBacklog ?? 0 }}</b><small>{{ t('soar.signalBacklog') }}</small></div><div><b>{{ deadLetters.length }}</b><small>{{ t('soar.deadLetters') }}</small></div><div><b>{{ Object.values(stats?.runsByStatus || {}).reduce((sum, value) => sum + value, 0) }}</b><small>{{ t('soar.projectedRuns') }}</small></div></div>
      <div class="soar-section-toolbar"><div><b>{{ t('soar.deadLetterOperations') }}</b><small>{{ t('soar.deadLetterHint') }}</small></div></div>
      <div v-if="rowError" role="alert" class="soar-feedback error">{{ rowError }}</div>
      <div class="soar-table-scroll"><table><thead><tr><th>{{ t('common.type') }}</th><th>{{ t('soar.runNode').split(' / ')[0] }}</th><th>{{ t('soar.signalKey') }}</th><th>{{ t('soar.attempts') }}</th><th>{{ t('soar.lastError') }}</th><th v-if="props.canOperate">{{ t('common.actions') }}</th></tr></thead><tbody><tr v-for="letter in deadLetters" :key="deadLetterKey(letter)"><td>{{ letter.kind || 'DISPATCH' }}<small>{{ letter.signalType || '' }}</small></td><td class="mono">{{ letter.runId }}</td><td class="mono">{{ letter.signalKey || '-' }}</td><td>{{ letter.attempts }}</td><td>{{ letter.lastError || '-' }}</td><td v-if="props.canOperate" class="nowrap"><el-button link size="small" :loading="deadLetterAction[deadLetterKey(letter)] === 'requeue'" :disabled="Boolean(deadLetterAction[deadLetterKey(letter)])" @click="requeue(letter)">{{ t('soar.requeue') }}</el-button><el-button link type="danger" size="small" :loading="deadLetterAction[deadLetterKey(letter)] === 'discard'" :disabled="Boolean(deadLetterAction[deadLetterKey(letter)])" @click="discard(letter)">{{ t('soar.discard') }}</el-button></td></tr></tbody></table><div v-if="!loading && !deadLetters.length" class="soar-empty">{{ t('soar.noDeadLetters') }}</div></div>
    </section>
    <el-drawer v-model="taskOpen" :before-close="taskGuard.beforeClose" :title="t('forms.task')" size="min(760px, 96vw)" :close-on-click-modal="false">
      <template v-if="selectedTask"><p>{{ selectedTask.runId }} · {{ selectedTask.nodeId }}</p><div v-if="formError" role="alert" class="soar-feedback error">{{ formError }}</div><SchemaInputForm :key="selectedTask.id" v-model="taskValue" :schema="selectedTask.formSchema" :disabled="taskBusy" @valid="taskValid = $event" /></template>
      <template #footer>
        <el-button @click="taskGuard.cancel">{{ t('common.cancel') }}</el-button>
        <el-button v-if="props.canCompleteTasks && selectedTask" type="primary" :loading="taskBusy" :disabled="!taskValid" @click="completeTask(selectedTask)">{{ t('forms.complete') }}</el-button>
      </template>
    </el-drawer>
  </el-card>
</template>

<style scoped>
.soar-control-plane { margin-top: 16px; border: 1px solid var(--ns-border); }
.soar-control-header, .soar-section-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 14px; }
.soar-subtitle, .soar-section-toolbar small { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 13px; }
.soar-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--ns-border); margin-bottom: 12px; }
.soar-tabs button { border: 0; border-bottom: 2px solid transparent; padding: 8px 11px; background: transparent; color: var(--ns-text-2); cursor: pointer; font: inherit; font-size: 13px; }
.soar-tabs button:focus-visible { outline: none; box-shadow: var(--ns-focus); }
.soar-tabs button:hover, .soar-tabs button.active { border-bottom-color: var(--ns-accent); color: var(--ns-accent); }
.soar-feedback { margin: 8px 0; padding: 7px 10px; border-radius: 5px; font-size: 13px; }.soar-feedback.success { color: var(--ns-success); background: color-mix(in srgb, var(--ns-success) 9%, transparent); }.soar-feedback.error { color: var(--ns-danger); background: color-mix(in srgb, var(--ns-danger) 9%, transparent); }
.soar-control-section { min-width: 0; }.soar-section-toolbar { margin-bottom: 10px; }.soar-section-toolbar > div:first-child { min-width: 0; }
.soar-version-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }.soar-version-option small, .soar-field-warning { color: var(--ns-warning); font-size: 12px; }.soar-field-warning, .soar-field-error { margin: 6px 0 0; }.soar-field-error { color: var(--ns-danger); font-size: 12px; }
.soar-test-box { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 9px; margin-bottom: 10px; }.soar-test-box pre { max-height: 100px; margin: 0; overflow: auto; padding: 7px; border: 1px solid var(--ns-border); border-radius: 4px; font-size: 13px; }
.soar-table-scroll { max-height: 330px; overflow: auto; }.soar-control-plane table { width: 100%; border-collapse: collapse; font-size: 13px; }.soar-control-plane th, .soar-control-plane td { padding: 7px 6px; border-bottom: 1px solid var(--ns-border); text-align: left; vertical-align: top; }.soar-control-plane th { color: var(--ns-text-3); font-size: 12px; text-transform: uppercase; }.soar-control-plane td b, .soar-control-plane td small { display: block; }.soar-control-plane td small { margin-top: 2px; color: var(--ns-text-3); font-size: 12px; }.mono { max-width: 300px; overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }.nowrap { white-space: nowrap; }.soar-empty { padding: 10px 0; color: var(--ns-text-3); font-size: 13px; }
.soar-inline-details { position: relative; display: inline-block; margin-left: 6px; color: var(--ns-text-2); font-size: 13px; }.soar-inline-details summary { cursor: pointer; }.soar-action-catalog { position: absolute; z-index: 2; right: 0; top: 22px; display: grid; width: min(520px, 80vw); max-height: 240px; overflow: auto; gap: 6px; padding: 9px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg); box-shadow: 0 5px 20px rgb(0 0 0 / 16%); }.soar-action-catalog span { display: flex; justify-content: space-between; gap: 10px; }.soar-action-catalog small { color: var(--ns-text-3); }.soar-task-input { min-width: 180px; }.soar-stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 14px; }.soar-stat-grid > div { padding: 10px; border: 1px solid var(--ns-border); border-radius: 5px; background: var(--ns-bg-subtle); }.soar-stat-grid b, .soar-stat-grid small { display: block; }.soar-stat-grid b { font-size: 20px; }.soar-stat-grid small { margin-top: 3px; color: var(--ns-text-3); font-size: 13px; }
@media (max-width: 850px) { .soar-test-box { grid-template-columns: 1fr; } }
@media (max-width: 560px) { .soar-control-header, .soar-section-toolbar { align-items: flex-start; flex-direction: column; }.soar-stat-grid { grid-template-columns: 1fr; }.soar-tabs { overflow-x: auto; }.soar-tabs button { white-space: nowrap; } }
</style>
