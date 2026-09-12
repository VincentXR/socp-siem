<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import 'element-plus/es/components/select/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import { ElOption, ElSelect } from 'element-plus/es/components/select/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { CONDITION_OPERATORS, compileCondition, parseCondition, type ExpressionCondition } from './conditionExpression'
import FieldConditionBuilder from '../../FieldConditionBuilder.vue'
import VariableSelector, { type VariableOption } from '../../VariableSelector.vue'
import { listActions, listConnections, listPlaybooks, listVersions, type SoarActionDescriptor, type SoarConnection } from '../../../api'
import { NODE_TYPE_ORDER, SOAR_NODE_REGISTRY } from './nodeRegistry'
import { rawNodeType, isUnsupportedNodeType, readSwitchCases, type SoarFlowApi } from './useDefinitionFlow'
import type { FieldDef, RuleCondition } from '../../../api'
import type { EditorNode, ValidationIssue } from './types'
import { useI18n } from '../../../composables/useI18n'
import { WORKBENCH_STATE } from '../../../app/workbenchState'

const props = withDefaults(defineProps<{ flow: SoarFlowApi; node: EditorNode | null; readOnly?: boolean }>(), { readOnly: false })

const { t } = useI18n()
const workbenchState = inject(WORKBENCH_STATE, null)

const END_OUTCOMES = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'SUPPRESSED', 'FAILED', 'TIMED_OUT', 'CANCELLED']

const nodeType = computed(() => (props.node ? rawNodeType(props.node) : ''))
const unsupported = computed(() => Boolean(props.node) && isUnsupportedNodeType(props.node!))

const typeOptions = computed(() => NODE_TYPE_ORDER.map(type => SOAR_NODE_REGISTRY[type]))

const variableOptions = computed<VariableOption[]>(() => {
  // Read the graph through graphRevision so a newly added node immediately
  // becomes available as a selectable output without changing raw definitions.
  props.flow.graphRevision.value
  const definition = props.flow.getDefinition()
  const options: VariableOption[] = [
    { value: 'trigger.event', label: t('soar.property.triggerEvent'), kind: 'trigger', description: t('soar.property.eventStartedRun') },
    { value: 'trigger.eventId', label: t('soar.property.triggerEventId'), kind: 'trigger' },
    { value: 'trigger.alertId', label: t('soar.property.triggerAlertId'), kind: 'trigger' },
    { value: 'trigger.entity', label: t('soar.property.triggerEntity'), kind: 'trigger' },
    { value: 'trigger.severity', label: t('soar.property.triggerSeverity'), kind: 'trigger' },
  ]
  for (const item of definition.nodes) {
    if (item.id === props.node?.id) continue
    const name = typeof item.name === 'string' && item.name.trim() ? item.name : item.id
    options.push({ value: `steps.${item.id}.output`, label: t('soar.property.nodeOutput', { name }), kind: 'node', description: item.id })
  }
  const seen = new Set<string>()
  return options.filter(option => !seen.has(option.value) && Boolean(seen.add(option.value)))
})

const conditionFields = computed<FieldDef[]>(() => variableOptions.value.map((option, index) => ({
  id: `soar-${index}`,
  fieldName: option.value,
  fieldLabel: option.label,
  fieldType: option.value.endsWith('severity') ? 'string' : 'string',
  source: option.kind || 'workflow',
  searchable: true,
  aggregatable: false,
  stored: true,
  description: option.description || '',
})))

/* ---------- ACTION catalog (loaded lazily) ---------- */
const actions = ref<SoarActionDescriptor[]>([])
const connections = ref<SoarConnection[]>([])
const actionCatalogState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
const subPlaybookVersions = ref<Array<{ id: string; playbookName: string; version: number; status: string }>>([])
const subPlaybookCatalogState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadActionCatalog(): Promise<void> {
  if (actionCatalogState.value === 'loading') return
  actionCatalogState.value = 'loading'
  try {
    const [actionResult, connectionResult] = await Promise.allSettled([
      listActions(),
      listConnections(0, 100),
    ])
    if (actionResult.status === 'fulfilled') actions.value = actionResult.value
    if (connectionResult.status === 'fulfilled') connections.value = connectionResult.value.items
    actionCatalogState.value = actionResult.status === 'rejected' || connectionResult.status === 'rejected' ? 'error' : 'loaded'
  } catch {
    actionCatalogState.value = 'error'
  }
}

watch(nodeType, (type) => {
  if (type !== 'ACTION') return
  const node = props.node
  if (node && typeof node.actionRef === 'string' && node.actionRef === '' && actions.value.length) {
    node.actionRef = actions.value[0].actionRef
    props.flow.touchAfterNodeEdit()
  }
  if (actionCatalogState.value !== 'loading' && actions.value.length === 0) {
    void loadActionCatalog()
  }
}, { immediate: true })

watch(actions, (catalog) => {
  const node = props.node
  if (!node || nodeType.value !== 'ACTION') return
  if (typeof node.actionRef === 'string' && node.actionRef === '' && catalog.length) {
    node.actionRef = catalog[0].actionRef
    props.flow.touchAfterNodeEdit()
  }
})

async function loadSubPlaybookCatalog(): Promise<void> {
  if (subPlaybookCatalogState.value === 'loading' || subPlaybookCatalogState.value === 'loaded') return
  subPlaybookCatalogState.value = 'loading'
  try {
    const page = await listPlaybooks(0, 100)
    const results = await Promise.allSettled(page.items.map(async playbook => {
      const versions = await listVersions(playbook.id)
      return versions
        .filter(version => version.status === 'PUBLISHED')
        .map(version => ({ id: version.id, playbookName: playbook.name, version: version.version, status: version.status }))
    }))
    subPlaybookVersions.value = results
      .filter((result): result is PromiseFulfilledResult<Array<{ id: string; playbookName: string; version: number; status: string }>> => result.status === 'fulfilled')
      .flatMap(result => result.value)
    subPlaybookCatalogState.value = results.some(result => result.status === 'rejected') ? 'error' : 'loaded'
  } catch {
    subPlaybookCatalogState.value = 'error'
  }
}

watch(nodeType, (type) => {
  if (type === 'SUB_PLAYBOOK') void loadSubPlaybookCatalog()
}, { immediate: true })

const actionRefKnown = computed(() => {
  const ref = props.node ? String(props.node.actionRef ?? '') : ''
  return !ref || actions.value.some(action => action.actionRef === ref)
})

/* ---------- scalar field helpers (empty trimmed string deletes) ---------- */
function scalar(node: EditorNode, field: string): string {
  const value = node[field]
  return typeof value === 'string' ? value : ''
}

function updateScalar(field: string, value: string): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  if (value.trim()) node[field] = value
  else delete node[field]
  props.flow.touchAfterNodeEdit()
}

/* ---------- APPROVAL config editing ---------- */
function approvalConfig(): Record<string, unknown> {
  const node = props.node
  const config = node?.config && typeof node.config === 'object' ? node.config as Record<string, unknown> : {}
  return config
}

function updateApprovalConfig(field: string, value: unknown): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const current = node.config && typeof node.config === 'object' ? node.config as Record<string, unknown> : {}
  node.config = { ...current, [field]: value }
  props.flow.touchAfterNodeEdit()
}

function updateApprovalConfigNumber(field: string, raw: string, max?: number): void {
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < 0) return
  if (max !== undefined && parsed > max) return
  updateApprovalConfig(field, parsed)
}

function tagsOf(value: Record<string, unknown>, field: string): string {
  const entry = value[field]
  return Array.isArray(entry) ? (entry as unknown[]).map(String).join(', ') : ''
}

function updateApprovalTags(field: string, raw: string): void {
  if (props.readOnly) return
  const values = raw.split(/[,，]/).map(value => value.trim()).filter(Boolean)
  const node = props.node
  if (!node) return
  const current = node.config && typeof node.config === 'object' ? node.config as Record<string, unknown> : {}
  const config = { ...current }
  if (values.length) config[field] = values
  else delete config[field]
  node.config = config
  props.flow.touchAfterNodeEdit()
}

function approvalNumberValue(field: string, fallback: number): string {
  const value = approvalConfig()[field]
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : String(fallback)
}

function approvalTagsValue(field: string): string {
  return tagsOf(approvalConfig(), field)
}

/* ---------- generic nested config/limits editing (FOREACH / PARALLEL) ---------- */
function nestedOf(area: 'config' | 'limits'): Record<string, unknown> {
  const node = props.node
  const value = node?.[area]
  return value && typeof value === 'object' ? value as Record<string, unknown> : {}
}

function updateNested(area: 'config' | 'limits', field: string, value: string): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const current = { ...nestedOf(area) }
  if (value.trim()) current[field] = value
  else delete current[field]
  node[area] = current
  props.flow.touchAfterNodeEdit()
}

function updateNestedNumber(area: 'config' | 'limits', field: string, raw: string, min: number, max: number): void {
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < min || parsed > max) return
  updateNested(area, field, String(parsed))
}

function nestedNumberValue(area: 'config' | 'limits', field: string, fallback: number): string {
  const value = nestedOf(area)[field]
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : String(fallback)
}

function nestedTextValue(area: 'config' | 'limits', field: string): string {
  const value = nestedOf(area)[field]
  return typeof value === 'string' ? value : ''
}

/* ---------- SWITCH case table (config.cases rows of value -> branch port) ---------- */
interface SwitchRow {
  value: string
  port: string
}

const switchRows = ref<SwitchRow[]>([])

function syncSwitchRows(): void {
  switchRows.value = props.node ? readSwitchCases(props.node).map(row => ({ value: row.value, port: row.port })) : []
}

watch(() => props.node, syncSwitchRows, { immediate: true })

/** Writes the rows into the same field layout the engine/validator read. */
function commitSwitchRows(refreshHandles = true): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const rawRows = switchRows.value.map(row => ({ value: row.value.trim(), port: row.port.trim() }))
  if (Array.isArray(node.cases)) {
    // A definition carrying a top-level `cases` array keeps that spelling
    // (the engine prefers it over config.cases).
    node.cases = rawRows
  } else {
    const config = { ...nestedOf('config') }
    config.cases = rawRows
    node.config = config
  }
  props.flow.touchAfterNodeEdit()
  // New/renamed case ports must appear (or disappear) as node handles.
  if (refreshHandles) props.flow.refreshPorts()
}

function updateSwitchValue(index: number, raw: string): void {
  if (props.readOnly || !switchRows.value[index]) return
  switchRows.value[index].value = raw
  commitSwitchRows(false)
}

function updateSwitchPort(index: number, raw: string): void {
  if (props.readOnly || !switchRows.value[index]) return
  switchRows.value[index].port = raw
  commitSwitchRows()
}

function addSwitchCase(): void {
  if (props.readOnly) return
  switchRows.value.push({ value: '', port: '' })
  commitSwitchRows()
}

function removeSwitchCase(index: number): void {
  if (props.readOnly) return
  switchRows.value.splice(index, 1)
  commitSwitchRows()
}

/* ---------- JSON editors ---------- */
const nodeConfigText = ref('{}')
const parametersText = ref('{}')
const targetText = ref('{}')

function stringifyJson(value: unknown): string {
  if (value === undefined || value === null) return '{}'
  try { return JSON.stringify(value, null, 2) } catch { return '{}' }
}

function syncJsonEditors(): void {
  const node = props.node
  if (!node) { nodeConfigText.value = '{}'; parametersText.value = '{}'; targetText.value = '{}'; return }
  nodeConfigText.value = stringifyJson(configOfNode(node))
  parametersText.value = stringifyJson(node.parameters)
  targetText.value = stringifyJson(node.target)
}

function configOfNode(node: EditorNode): Record<string, unknown> {
  const config: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(node)) {
    if (!['id', 'type', 'name'].includes(key)) config[key] = value
  }
  return config
}

function applyNodeConfigJson(): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  try {
    const parsed = JSON.parse(nodeConfigText.value) as Record<string, unknown>
    for (const key of Object.keys(node)) if (!['id', 'type', 'name'].includes(key)) delete node[key]
    Object.assign(node, parsed)
    props.flow.touchAfterNodeEdit()
  } catch {
    // invalid JSON is surfaced by leaving the text area untouched
  }
}

function applyJsonInto(field: string, text: string): void {
  const node = props.node
  if (!node) return
  try {
    const parsed = JSON.parse(text) as unknown
    if (parsed === null || Array.isArray(parsed)) return
    if (typeof parsed === 'object') node[field] = parsed
  } catch {
    // ignore invalid JSON until Apply is re-run with valid content
  }
}

function commitParameters(): void {
  if (props.readOnly) return
  applyJsonInto('parameters', parametersText.value)
  props.flow.touchAfterNodeEdit()
}

function commitTarget(): void {
  if (props.readOnly) return
  applyJsonInto('target', targetText.value)
  props.flow.touchAfterNodeEdit()
}

/* ---------- ACTION retry row ---------- */
const retryConfig = computed(() => {
  const node = props.node
  if (!node) return null
  const retry = node.retry && typeof node.retry === 'object' ? node.retry as Record<string, unknown> : null
  return retry
})

function hasRetry(): boolean {
  return Boolean(props.node && props.node.retry && typeof props.node.retry === 'object')
}

function updateRetry(field: string, raw: string, min: number, max: number): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < min || parsed > max) return
  const current = node.retry && typeof node.retry === 'object' ? node.retry as Record<string, unknown> : {}
  node.retry = { ...current, [field]: parsed }
  props.flow.touchAfterNodeEdit()
}

function addRetry(): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  node.retry = { maxAttempts: 3, backoffSeconds: 30 }
  props.flow.touchAfterNodeEdit()
}

function removeRetry(): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  delete node.retry
  props.flow.touchAfterNodeEdit()
}

/* ---------- type change / removal ---------- */
function onTypeChange(event: Event): void {
  if (props.readOnly) return
  const target = event.target as HTMLSelectElement
  if (!props.node) return
  props.flow.updateNodeType(props.node.id, target.value)
}

/* ---------- selected node's own validation issues ---------- */
const ownIssues = computed<ValidationIssue[]>(() => {
  const id = props.node?.id
  if (!id) return []
  const issues = props.flow.nodeIssues(id)
  return [...issues.errors, ...issues.warnings]
})

watch(() => props.node, syncJsonEditors, { immediate: true })

function showAdvanced(): void {
  syncJsonEditors()
}

const selectedAction = computed<SoarActionDescriptor | undefined>(() => {
  const ref = props.node ? String(props.node.actionRef ?? '') : ''
  return ref ? actions.value.find(action => action.actionRef === ref) : undefined
})

const compatibleConnections = computed(() => {
  const connectorId = selectedAction.value?.connectorId
  if (!connectorId) return connections.value
  const compatible = connections.value.filter(connection => connection.connectorType === connectorId)
  return compatible.length ? compatible : connections.value
})

const conditionRows = ref<ExpressionCondition[]>([])
const conditionError = ref('')

function syncConditionRows(): void {
  const expression = props.node ? scalar(props.node, 'expression') : ''
  conditionRows.value = parseCondition(expression)
  conditionError.value = ''
}

function commitConditionRows(rows: ExpressionCondition[]): void {
  if (props.readOnly) return
  conditionRows.value = rows.map(row => ({ ...row }))
  try {
    const expression = compileCondition(conditionRows.value)
    // An empty condition must not become an unconditional true branch.
    updateScalar('expression', expression || 'false')
    conditionError.value = ''
  } catch (error) {
    conditionError.value = error instanceof Error ? error.message : String(error)
  }
}

watch(() => props.node, syncConditionRows, { immediate: true })

function targetPath(): string {
  const target = props.node?.target
  if (!target || typeof target !== 'object' || Array.isArray(target)) return ''
  const record = target as Record<string, unknown>
  return typeof record.path === 'string' ? record.path : typeof record.ref === 'string' ? record.ref : ''
}

function updateTargetPath(value: string): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const current = node.target && typeof node.target === 'object' && !Array.isArray(node.target)
    ? { ...(node.target as Record<string, unknown>) } : {}
  if (value.trim()) current.path = value.trim()
  else delete current.path
  node.target = current
  syncJsonEditors()
  props.flow.touchAfterNodeEdit()
}

const approvalRoleOptions = ['admin', 'analyst', 'operator', 'approver']

const operatorOptions = computed(() => {
  const current = nestedTextValue('config', 'assignee')
  return Array.from(new Set([
    ...(workbenchState?.operatorOptions.value ?? []),
    current,
  ].filter(Boolean)))
})

function approvalListValue(field: string): string[] {
  const value = approvalConfig()[field]
  return Array.isArray(value) ? value.map(String) : []
}

function updateApprovalList(field: string, value: string[]): void {
  updateApprovalConfig(field, value.filter(item => item.trim()))
}

function asStringList(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : []
}

interface ManualFieldRow {
  name: string
  title: string
  type: string
  required: boolean
}

function manualSchema(): { schema: Record<string, unknown>; inConfig: boolean } {
  const node = props.node
  const config = node?.config && typeof node.config === 'object' ? node.config as Record<string, unknown> : {}
  const configSchema = config.formSchema && typeof config.formSchema === 'object' && !Array.isArray(config.formSchema)
    ? config.formSchema as Record<string, unknown> : null
  if (configSchema) return { schema: configSchema, inConfig: true }
  const topSchema = node?.formSchema && typeof node.formSchema === 'object' && !Array.isArray(node.formSchema)
    ? node.formSchema as Record<string, unknown> : null
  if (topSchema) return { schema: topSchema, inConfig: false }
  return { schema: { type: 'object', properties: {}, required: [] }, inConfig: true }
}

const manualFieldRows = computed<ManualFieldRow[]>(() => {
  const { schema } = manualSchema()
  const properties = schema.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)
    ? schema.properties as Record<string, unknown> : {}
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  return Object.entries(properties).map(([name, value]) => {
    const field = value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
    return { name, title: String(field.title ?? name), type: String(field.type ?? 'string'), required: required.has(name) }
  })
})

function writeManualSchema(schema: Record<string, unknown>): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const location = manualSchema()
  if (location.inConfig) {
    const config = node.config && typeof node.config === 'object' && !Array.isArray(node.config) ? { ...(node.config as Record<string, unknown>) } : {}
    config.formSchema = schema
    node.config = config
  } else node.formSchema = schema
  props.flow.touchAfterNodeEdit()
}

function updateManualField(name: string, patch: Partial<ManualFieldRow>): void {
  if (props.readOnly) return
  const { schema } = manualSchema()
  const properties = schema.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)
    ? { ...(schema.properties as Record<string, unknown>) } : {}
  const current = properties[name] && typeof properties[name] === 'object' && !Array.isArray(properties[name])
    ? { ...(properties[name] as Record<string, unknown>) } : {}
  if (patch.title !== undefined) current.title = patch.title
  if (patch.type !== undefined) current.type = patch.type
  properties[name] = current
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  if (patch.required === true) required.add(name)
  if (patch.required === false) required.delete(name)
  writeManualSchema({ ...schema, type: 'object', properties, required: [...required] })
}

function addManualField(): void {
  if (props.readOnly) return
  const existing = new Set(manualFieldRows.value.map(field => field.name))
  let index = 1
  while (existing.has(`field_${index}`)) index += 1
  const name = `field_${index}`
  const { schema } = manualSchema()
  const properties = schema.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)
    ? { ...(schema.properties as Record<string, unknown>) } : {}
  properties[name] = { title: name, type: 'string' }
  writeManualSchema({ ...schema, type: 'object', properties, required: Array.isArray(schema.required) ? schema.required : [] })
}

function removeManualField(name: string): void {
  if (props.readOnly) return
  const { schema } = manualSchema()
  const properties = schema.properties && typeof schema.properties === 'object' && !Array.isArray(schema.properties)
    ? { ...(schema.properties as Record<string, unknown>) } : {}
  delete properties[name]
  const required = Array.isArray(schema.required) ? schema.required.map(String).filter(item => item !== name) : []
  writeManualSchema({ ...schema, type: 'object', properties, required })
}

interface ActionInputField {
  key: string
  label: string
  type: string
  description?: string
  enum?: string[]
  required: boolean
}

const actionInputFields = computed<ActionInputField[]>(() => {
  const schema = selectedAction.value?.inputSchema
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return []
  const properties = (schema as Record<string, unknown>).properties
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return []
  const requiredValues = (schema as Record<string, unknown>).required
  const required = Array.isArray(requiredValues) ? new Set(requiredValues.map(String)) : new Set<string>()
  return Object.entries(properties as Record<string, unknown>).flatMap(([key, value]) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return []
    const field = value as Record<string, unknown>
    if (field.type && !['string', 'number', 'integer', 'boolean'].includes(String(field.type))) return []
    return [{
      key,
      label: String(field.title ?? key),
      type: String(field.type ?? 'string'),
      description: typeof field.description === 'string' ? field.description : undefined,
      enum: Array.isArray(field.enum) ? field.enum.map(String) : undefined,
      required: required.has(key),
    }]
  })
})

function parameterValue(key: string): string {
  const parameters = props.node?.parameters
  if (!parameters || typeof parameters !== 'object' || Array.isArray(parameters)) return ''
  const value = (parameters as Record<string, unknown>)[key]
  return value == null ? '' : typeof value === 'object' ? JSON.stringify(value) : String(value)
}

function updateParameterValue(field: ActionInputField, value: string): void {
  if (props.readOnly) return
  const node = props.node
  if (!node) return
  const current = node.parameters && typeof node.parameters === 'object' && !Array.isArray(node.parameters)
    ? { ...(node.parameters as Record<string, unknown>) } : {}
  if (!value.trim()) delete current[field.key]
  else if (field.type === 'number' || field.type === 'integer') {
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) return
    current[field.key] = parsed
  }
  else if (field.type === 'boolean') current[field.key] = value === 'true'
  else current[field.key] = value
  node.parameters = current
  syncJsonEditors()
  props.flow.touchAfterNodeEdit()
}

function riskTag(risk: string): 'danger' | 'warning' | 'success' | 'info' {
  if (risk === 'CRITICAL' || risk === 'HIGH') return 'danger'
  if (risk === 'MEDIUM') return 'warning'
  if (risk === 'LOW') return 'success'
  return 'info'
}

function connectionRefKnown(): boolean {
  const node = props.node
  const ref = node ? String(node.connectionRef ?? '') : ''
  return !ref || connections.value.some(connection => connection.id === ref)
}

function subPlaybookVersionKnown(id: string): boolean {
  return subPlaybookVersions.value.some(version => version.id === id)
}
</script>

<template>
  <aside class="soar-flow-inspector" :aria-label="t('soar.property.node')">
    <div class="soar-panel-title">{{ t('soar.propertyPanelTitle') }}</div>
    <div v-if="props.readOnly" class="soar-flow-inspector-readonly">{{ t('soar.readOnly') }}</div>

    <div v-if="node" class="soar-flow-inspector-content" :class="{ 'soar-flow-inspector-content-readonly': props.readOnly }">
      <div class="soar-flow-inspector-head">
        <div>
        <span class="soar-flow-id-label">ID</span>
          <code class="soar-flow-node-id">{{ node.id }}</code>
        </div>
        <el-button v-if="!props.readOnly" size="small" type="danger" plain :disabled="nodeType === 'START'" @click="flow.removeNode(node.id)">{{ t('common.delete') }}</el-button>
      </div>

      <!-- Unsupported / read-only banner -->
      <div v-if="unsupported" class="soar-flow-readonly-banner">
        <b>{{ t('soar.unsupportedNode') }}</b>
        <p>{{ t('soar.readOnlyHint') }}</p>
      </div>

      <label>
        {{ t('soar.property.type') }}
        <select :value="nodeType" :disabled="unsupported || props.readOnly" @change="onTypeChange">
          <option v-for="option in typeOptions" :key="option.type" :value="option.type" :disabled="option.comingSoon">
            {{ option.type }}
          </option>
        </select>
      </label>

      <label v-if="!unsupported">
        {{ t('soar.property.name') }}
        <input :value="scalar(node, 'name')" :disabled="props.readOnly" :placeholder="t('soar.property.nodeNamePlaceholder')" @input="updateScalar('name', ($event.target as HTMLInputElement).value)" />
      </label>

      <!-- START -->
      <div v-if="nodeType === 'START'" class="soar-flow-hint">
        {{ t('soar.startHint') }}
      </div>

      <!-- ACTION -->
      <template v-if="nodeType === 'ACTION'">
        <label>
          {{ t('soar.property.action') }}
          <el-select
            :model-value="scalar(node, 'actionRef')"
            :disabled="props.readOnly"
            filterable
            default-first-option
            clearable
            :placeholder="t('soar.property.searchAction')"
            @change="updateScalar('actionRef', String($event ?? ''))"
          >
            <el-option v-if="scalar(node, 'actionRef') && !actionRefKnown" :label="scalar(node, 'actionRef')" :value="scalar(node, 'actionRef')">
              <div class="soar-flow-option"><b>{{ scalar(node, 'actionRef') }}</b><small>{{ t('soar.property.unregisteredReference') }}</small></div>
            </el-option>
            <el-option v-for="action in actions" :key="action.actionRef" :value="action.actionRef" :label="action.displayName">
              <div class="soar-flow-option"><b>{{ action.displayName }}</b><small>{{ action.actionRef }} · {{ action.riskLevel }} · {{ action.sideEffect }}</small></div>
            </el-option>
          </el-select>
        </label>
        <div class="soar-flow-catalog-toolbar">
          <el-tag v-if="!actionRefKnown && scalar(node, 'actionRef')" size="small" type="warning">{{ t('soar.property.unregisteredShort') }}</el-tag>
          <el-tag v-else-if="selectedAction" size="small" :type="riskTag(selectedAction.riskLevel)">{{ selectedAction.riskLevel }}</el-tag>
          <el-button size="small" :loading="actionCatalogState === 'loading'" @click="loadActionCatalog">{{ t('common.refresh') }}</el-button>
        </div>

        <label>
          {{ t('soar.property.connection') }}
          <el-select
            :model-value="scalar(node, 'connectionRef')"
            :disabled="props.readOnly"
            filterable
            clearable
            :placeholder="t('soar.property.searchConnection')"
            @change="updateScalar('connectionRef', String($event ?? ''))"
          >
            <el-option v-if="scalar(node, 'connectionRef') && !connectionRefKnown()" :label="scalar(node, 'connectionRef')" :value="scalar(node, 'connectionRef')">
              <div class="soar-flow-option"><b>{{ scalar(node, 'connectionRef') }}</b><small>{{ t('soar.property.unregisteredReference') }}</small></div>
            </el-option>
            <el-option v-for="connection in compatibleConnections" :key="connection.id" :value="connection.id" :label="connection.name">
              <div class="soar-flow-option"><b>{{ connection.name }}</b><small>{{ connection.connectorType }} · {{ connection.status }}</small></div>
            </el-option>
          </el-select>
        </label>
        <div v-if="scalar(node, 'connectionRef') && !connectionRefKnown()" class="soar-flow-warn-line">{{ t('soar.property.connectionNotCatalog') }}</div>

        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.parameters') }}</span>
          <small class="soar-flow-hint">{{ t('soar.property.parameterHint') }}</small>
          <div v-if="actionInputFields.length" class="soar-flow-parameter-form">
            <label v-for="field in actionInputFields" :key="field.key">
              <span>{{ field.label }}<i v-if="field.required">*</i></span>
              <el-select v-if="field.enum?.length" :model-value="parameterValue(field.key)" :disabled="props.readOnly" clearable @change="updateParameterValue(field, String($event ?? ''))">
                <el-option v-for="value in field.enum" :key="value" :label="value" :value="value" />
              </el-select>
              <el-select v-else-if="field.type === 'boolean'" :model-value="parameterValue(field.key)" :disabled="props.readOnly" clearable @change="updateParameterValue(field, String($event ?? ''))"><el-option label="true" value="true" /><el-option label="false" value="false" /></el-select>
              <VariableSelector v-else-if="field.type === 'string'" :model-value="parameterValue(field.key)" :variables="variableOptions" :disabled="props.readOnly" placeholder="Select or enter a value" @update:model-value="value => updateParameterValue(field, value)" />
              <el-input v-else :model-value="parameterValue(field.key)" :disabled="props.readOnly" :type="field.type === 'number' || field.type === 'integer' ? 'number' : 'text'" @update:model-value="value => updateParameterValue(field, String(value ?? ''))" />
              <small v-if="field.description">{{ field.description }}</small>
            </label>
          </div>
          <details class="soar-flow-advanced-details"><summary>{{ t('soar.property.advancedParameters') }}</summary><textarea v-model="parametersText" :readonly="props.readOnly" rows="4" spellcheck="false" /><el-button v-if="!props.readOnly" size="small" @click="commitParameters">{{ t('soar.property.applyJson') }}</el-button></details>
        </div>
        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.target') }}</span>
          <label class="soar-flow-common-field">
            {{ t('soar.property.commonTargetPath') }}
            <VariableSelector :model-value="targetPath()" :variables="variableOptions" :disabled="props.readOnly" :placeholder="t('soar.property.selectEventOutput')" @update:model-value="updateTargetPath" />
            <small>{{ t('soar.property.targetStorageHint') }}</small>
          </label>
          <details class="soar-flow-advanced-details" open><summary>{{ t('soar.property.advancedTarget') }}</summary><textarea v-model="targetText" :readonly="props.readOnly" rows="4" spellcheck="false" /><el-button v-if="!props.readOnly" size="small" @click="commitTarget">{{ t('soar.property.applyJson') }}</el-button></details>
        </div>

        <div v-if="hasRetry()" class="soar-flow-retry-grid">
          <label>{{ t('soar.property.maxAttempts') }}<input type="number" min="1" max="10" :disabled="props.readOnly" :value="String(retryConfig?.maxAttempts ?? 1)" @input="updateRetry('maxAttempts', ($event.target as HTMLInputElement).value, 1, 10)" /></label>
          <label>{{ t('soar.property.backoffSeconds') }}<input type="number" min="0" max="300" :disabled="props.readOnly" :value="String(retryConfig?.backoffSeconds ?? 0)" @input="updateRetry('backoffSeconds', ($event.target as HTMLInputElement).value, 0, 300)" /></label>
          <el-button v-if="!props.readOnly" size="small" plain @click="removeRetry">{{ t('soar.property.removeRetry') }}</el-button>
        </div>
        <el-button v-else-if="!props.readOnly" size="small" plain @click="addRetry">{{ t('soar.property.addRetry') }}</el-button>
      </template>

      <!-- CONDITION -->
      <template v-if="nodeType === 'CONDITION'">
        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.visualCondition') }}</span>
          <el-select v-if="conditionRows.length" :model-value="conditionRows[0]?.literalType || 'string'" :disabled="props.readOnly" @change="value => commitConditionRows(conditionRows.map(row => ({ ...row, literalType: value })))">
            <el-option :label="t('soar.property.text')" value="string" /><el-option :label="t('soar.property.integer')" value="number" /><el-option :label="t('soar.property.boolean')" value="boolean" />
          </el-select>
          <FieldConditionBuilder
            :model-value="conditionRows"
            :operators="Object.keys(CONDITION_OPERATORS)"
            :max-conditions="1"
            :fields="conditionFields"
            :read-only="props.readOnly"
            :title="t('soar.property.fieldOperatorValue')"
            :add-label="t('soar.property.addCondition')"
            :empty-hint="t('soar.property.complexConditionHint')"
            :field-placeholder="t('soar.property.selectWorkflowField')"
            :value-placeholder="t('soar.property.valuePlaceholder')"
            @update:model-value="commitConditionRows"
          />
          <p v-if="conditionError" role="alert" class="soar-flow-hint">{{ conditionError }}</p>
          <small class="soar-flow-hint">{{ t('soar.property.complexConditionHint') }}</small>
        </div>
        <label>
          {{ t('soar.property.expression') }}
          <input :value="scalar(node, 'expression')" :disabled="props.readOnly" placeholder="trigger.severity == 'HIGH'" @input="updateScalar('expression', ($event.target as HTMLInputElement).value)" />
        </label>
      </template>

      <!-- SWITCH -->
      <template v-if="nodeType === 'SWITCH'">
        <label>
          {{ t('soar.property.expression') }}
          <input :value="scalar(node, 'expression')" :disabled="props.readOnly" placeholder="trigger.severity" @input="updateScalar('expression', ($event.target as HTMLInputElement).value)" />
        </label>
        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.cases') }}</span>
          <div v-for="(row, index) in switchRows" :key="index" class="soar-flow-switch-case-row">
            <input :value="row.value" :disabled="props.readOnly" :placeholder="t('soar.property.value')" @input="updateSwitchValue(index, ($event.target as HTMLInputElement).value)" />
            <input :value="row.port" :disabled="props.readOnly" :placeholder="t('soar.property.port')" @input="updateSwitchPort(index, ($event.target as HTMLInputElement).value)" />
            <el-button v-if="!props.readOnly" size="small" plain @click="removeSwitchCase(index)">{{ t('soar.property.remove') }}</el-button>
          </div>
          <el-button v-if="!props.readOnly" size="small" plain @click="addSwitchCase">{{ t('soar.property.addCase') }}</el-button>
          <p class="soar-flow-hint">{{ t('soar.property.caseHint') }}</p>
        </div>
      </template>

      <!-- END -->
      <label v-if="nodeType === 'END'">
        {{ t('soar.property.outcome') }}
        <select :value="scalar(node, 'outcome') || 'SUCCEEDED'" :disabled="props.readOnly" @change="updateScalar('outcome', ($event.target as HTMLSelectElement).value)">
          <option v-for="outcome in END_OUTCOMES" :key="outcome" :value="outcome">{{ outcome }}</option>
        </select>
      </label>

      <!-- APPROVAL -->
      <template v-if="nodeType === 'APPROVAL'">
        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.approvalPolicy') }}</span>
          <label>{{ t('soar.property.timeoutSeconds') }}
            <input type="number" min="0" :max="7 * 24 * 3600" :disabled="props.readOnly" :value="approvalNumberValue('timeoutSeconds', 86400)" @input="updateApprovalConfigNumber('timeoutSeconds', ($event.target as HTMLInputElement).value, 7 * 24 * 3600)" />
          </label>
          <label>{{ t('soar.property.requiredApprovals') }}
            <input type="number" min="1" :disabled="props.readOnly" :value="approvalNumberValue('requiredApprovals', 1)" @input="updateApprovalConfigNumber('requiredApprovals', ($event.target as HTMLInputElement).value)" />
          </label>
          <label>{{ t('soar.property.allowedRoles') }}
            <el-select multiple filterable default-first-option :disabled="props.readOnly" :model-value="approvalListValue('allowedRoles')" :placeholder="t('soar.property.selectRoles')" @change="updateApprovalList('allowedRoles', asStringList($event))">
              <el-option v-for="role in approvalRoleOptions" :key="role" :label="role" :value="role" />
            </el-select>
          </label>
          <label>{{ t('soar.property.allowedGroups') }}
            <el-select multiple filterable allow-create default-first-option :disabled="props.readOnly" :model-value="approvalListValue('allowedGroups')" :placeholder="t('soar.property.selectGroups')" @change="updateApprovalList('allowedGroups', asStringList($event))">
              <el-option v-for="group in approvalListValue('allowedGroups')" :key="group" :label="group" :value="group" />
            </el-select>
          </label>
          <label>{{ t('soar.property.approverRoles') }}
            <el-select multiple filterable default-first-option :disabled="props.readOnly" :model-value="approvalListValue('approverRoles')" :placeholder="t('soar.property.selectRoles')" @change="updateApprovalList('approverRoles', asStringList($event))">
              <el-option v-for="role in approvalRoleOptions" :key="role" :label="role" :value="role" />
            </el-select>
          </label>
          <label>{{ t('soar.property.approverGroups') }}
            <el-select multiple filterable allow-create default-first-option :disabled="props.readOnly" :model-value="approvalListValue('approverGroups')" :placeholder="t('soar.property.selectGroups')" @change="updateApprovalList('approverGroups', asStringList($event))">
              <el-option v-for="group in approvalListValue('approverGroups')" :key="group" :label="group" :value="group" />
            </el-select>
          </label>
          <small class="soar-flow-hint">{{ t('forms.groupFallback') }}</small>
        </div>
      </template>

      <!-- FOREACH -->
      <template v-if="nodeType === 'FOREACH'">
        <label>
          {{ t('soar.property.itemsPath') }}
          <VariableSelector :model-value="nestedTextValue('config', 'itemsPath')" :variables="variableOptions" :disabled="props.readOnly" :placeholder="t('soar.property.selectCollectionOutput')" @update:model-value="value => updateNested('config', 'itemsPath', value)" />
        </label>
        <label>
          {{ t('soar.property.itemVariable') }}
          <VariableSelector :model-value="nestedTextValue('config', 'itemVariable')" :variables="variableOptions" :disabled="props.readOnly" placeholder="vars.item" @update:model-value="value => updateNested('config', 'itemVariable', value)" />
        </label>
        <div class="soar-flow-retry-grid">
          <label>{{ t('soar.property.concurrency') }}<input type="number" min="1" max="10" :disabled="props.readOnly" :value="nestedNumberValue('limits', 'concurrency', 1)" @input="updateNestedNumber('limits', 'concurrency', ($event.target as HTMLInputElement).value, 1, 10)" /></label>
          <label>{{ t('soar.property.maxItems') }}<input type="number" min="1" max="100" :disabled="props.readOnly" :value="nestedNumberValue('limits', 'maxItems', 100)" @input="updateNestedNumber('limits', 'maxItems', ($event.target as HTMLInputElement).value, 1, 100)" /></label>
        </div>
      </template>

      <!-- PARALLEL -->
      <label v-if="nodeType === 'PARALLEL'">
        {{ t('soar.property.maxParallelism') }}
        <input type="number" min="1" max="10" :disabled="props.readOnly" :value="nestedNumberValue('limits', 'maxParallelism', 2)" @input="updateNestedNumber('limits', 'maxParallelism', ($event.target as HTMLInputElement).value, 1, 10)" />
      </label>

      <!-- JOIN -->
      <label v-if="nodeType === 'JOIN'">
        {{ t('soar.property.strategy') }}
        <select :value="scalar(node, 'strategy') || 'ALL_SUCCESS'" :disabled="props.readOnly" @change="updateScalar('strategy', ($event.target as HTMLSelectElement).value)">
          <option v-for="strategy in ['ALL_SUCCESS', 'ALL_DONE', 'ANY_SUCCESS']" :key="strategy" :value="strategy">{{ strategy }}</option>
        </select>
      </label>

      <!-- SUB_PLAYBOOK -->
      <label v-if="nodeType === 'SUB_PLAYBOOK'">
        {{ t('soar.property.publishedVersion') }}
        <el-select :model-value="scalar(node, 'playbookVersionId')" :disabled="props.readOnly" filterable default-first-option clearable :loading="subPlaybookCatalogState === 'loading'" :placeholder="t('soar.property.searchPublishedVersion')" @change="updateScalar('playbookVersionId', String($event ?? ''))">
          <el-option v-if="scalar(node, 'playbookVersionId') && !subPlaybookVersionKnown(scalar(node, 'playbookVersionId'))" :label="scalar(node, 'playbookVersionId')" :value="scalar(node, 'playbookVersionId')" />
          <el-option v-for="version in subPlaybookVersions" :key="version.id" :label="`${version.playbookName} · Revision ${version.version}`" :value="version.id"><div class="soar-flow-option"><b>{{ version.playbookName }} · Revision {{ version.version }}</b><small>{{ version.id }} · {{ version.status }}</small></div></el-option>
        </el-select>
        <small v-if="subPlaybookCatalogState === 'error'" class="soar-flow-catalog-warning">{{ t('soar.property.versionCatalogUnavailable') }}</small>
      </label>

      <!-- DELAY -->
      <label v-if="nodeType === 'DELAY'">
        {{ t('soar.property.durationSeconds') }}
        <input type="number" min="1" max="86400" :disabled="props.readOnly" :value="nestedNumberValue('config', 'durationSeconds', 60)" @input="updateNestedNumber('config', 'durationSeconds', ($event.target as HTMLInputElement).value, 1, 86400)" />
      </label>

      <!-- SET_VARIABLE -->
      <template v-if="nodeType === 'SET_VARIABLE'">
        <label>
          {{ t('soar.property.variableName') }}
          <input :value="nestedTextValue('config', 'name')" :disabled="props.readOnly" placeholder="vars.note" @input="updateNested('config', 'name', ($event.target as HTMLInputElement).value)" />
        </label>
        <label>
          {{ t('common.value') }}
          <VariableSelector :model-value="nestedTextValue('config', 'value')" :variables="variableOptions" :disabled="props.readOnly" :placeholder="t('soar.property.selectValue')" @update:model-value="value => updateNested('config', 'value', value)" />
        </label>
      </template>

      <!-- MANUAL_TASK -->
      <template v-if="nodeType === 'MANUAL_TASK'">
        <label>
          {{ t('soar.property.timeoutSeconds') }}
          <input type="number" min="0" :max="7 * 24 * 3600" :disabled="props.readOnly" :value="nestedNumberValue('config', 'timeoutSeconds', 86400)" @input="updateNestedNumber('config', 'timeoutSeconds', ($event.target as HTMLInputElement).value, 0, 7 * 24 * 3600)" />
        </label>
        <label>
          {{ t('soar.property.assignee') }}
          <el-select :model-value="nestedTextValue('config', 'assignee')" :disabled="props.readOnly" filterable default-first-option clearable :placeholder="t('soar.property.selectOperatorVariable')" @change="updateNested('config', 'assignee', String($event ?? ''))">
            <el-option v-for="operator in operatorOptions" :key="`operator-${operator}`" :label="operator" :value="operator" />
            <el-option v-for="variable in variableOptions" :key="`variable-${variable.value}`" :label="`${variable.label} · variable`" :value="variable.value" />
          </el-select>
        </label>
        <div class="soar-flow-inspector-section manual-task-schema">
          <div class="soar-flow-section-header"><span>{{ t('soar.property.taskFields') }}</span><el-button v-if="!props.readOnly" size="small" plain @click="addManualField">{{ t('soar.property.addField') }}</el-button></div>
          <div v-for="field in manualFieldRows" :key="field.name" class="manual-task-field-row">
            <input :value="field.title" :disabled="props.readOnly" :aria-label="`${t('soar.property.fieldLabel')} ${field.name}`" @input="updateManualField(field.name, { title: ($event.target as HTMLInputElement).value })" />
            <select :value="field.type" :disabled="props.readOnly" :aria-label="`${t('soar.property.fieldType')} ${field.name}`" @change="updateManualField(field.name, { type: ($event.target as HTMLSelectElement).value })"><option value="string">{{ t('soar.property.textType') }}</option><option value="number">{{ t('soar.property.numberType') }}</option><option value="boolean">{{ t('soar.property.booleanType') }}</option></select>
            <label class="manual-task-required"><input type="checkbox" :checked="field.required" :disabled="props.readOnly" @change="updateManualField(field.name, { required: ($event.target as HTMLInputElement).checked })" /> {{ t('soar.property.required') }}</label>
            <el-button v-if="!props.readOnly" link type="danger" :aria-label="t('soar.property.removeField', { name: field.name })" @click="removeManualField(field.name)">×</el-button>
          </div>
          <p class="soar-flow-hint">{{ t('soar.property.manualTaskHint') }}</p>
        </div>
      </template>

      <!-- Generic secondary fields for read-only types (raw display only) -->
      <template v-if="unsupported">
        <div class="soar-flow-inspector-section">
          <span>{{ t('soar.property.rawFields') }}</span>
          <pre class="soar-flow-raw-json">{{ stringifyJson(node) }}</pre>
        </div>
      </template>

      <!-- Advanced node JSON -->
      <div class="soar-flow-inspector-section">
        <span>{{ t('soar.property.advancedNodeJson') }}</span>
        <textarea v-model="nodeConfigText" :readonly="props.readOnly" rows="8" spellcheck="false" @focus="showAdvanced" />
        <el-button v-if="!props.readOnly" size="small" @click="applyNodeConfigJson">{{ t('soar.property.applyNodeJson') }}</el-button>
      </div>

      <!-- Selected node validation issues -->
      <div v-if="ownIssues.length" class="soar-flow-own-issues">
        <span class="soar-flow-own-issues-title">{{ t('soar.property.validationIssues') }}</span>
        <div v-for="(issue, index) in ownIssues" :key="`${issue.code}-${index}`" class="soar-flow-issue-row" :class="{ warning: issue.severity === 'WARNING' }">
          <b>{{ issue.code || t('soar.property.issue') }}</b><span>{{ issue.path || '' }}</span><p>{{ issue.message }}</p>
        </div>
      </div>
    </div>

    <div v-else class="soar-flow-empty">{{ t('soar.property.selectNode') }}</div>
  </aside>
</template>

<style scoped>
.soar-flow-inspector {
  min-width: 0;
  padding: 10px;
  border-left: 1px solid var(--ns-border);
  background: var(--ns-bg-subtle);
}

.soar-flow-inspector-readonly {
  margin: 0 0 10px;
  padding: 7px 8px;
  border: 1px solid color-mix(in srgb, var(--ns-warning) 40%, transparent);
  border-radius: 5px;
  background: color-mix(in srgb, var(--ns-warning) 8%, transparent);
  color: var(--ns-warning);
  font-size: 11px;
  line-height: 1.45;
}

.soar-flow-inspector-content-readonly {
  pointer-events: none;
  opacity: 0.92;
}

.soar-flow-inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.soar-flow-id-label {
  display: block;
  color: var(--ns-text-3);
  font-size: 9px;
  text-transform: uppercase;
}

.soar-flow-node-id {
  overflow-wrap: anywhere;
  color: var(--ns-text-2);
  font-size: 11px;
}

.soar-flow-readonly-banner {
  margin-bottom: 9px;
  padding: 7px 8px;
  border: 1px solid color-mix(in srgb, var(--ns-warning) 40%, transparent);
  border-radius: 5px;
  background: color-mix(in srgb, var(--ns-warning) 8%, transparent);
}

.soar-flow-readonly-banner b {
  color: var(--ns-warning);
  font-size: 10px;
}

.soar-flow-readonly-banner p {
  margin: 3px 0 0;
  color: var(--ns-text-2);
  font-size: 10px;
  line-height: 1.45;
}

.soar-flow-hint {
  margin: 2px 0 8px;
  color: var(--ns-text-3);
  font-size: 10px;
  line-height: 1.5;
}

.soar-flow-inspector label {
  display: block;
  margin-bottom: 9px;
  color: var(--ns-text-2);
  font-size: 10px;
}

.soar-flow-inspector label input,
.soar-flow-inspector label select {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 3px;
}

.soar-flow-inspector input,
.soar-flow-inspector select,
.soar-flow-inspector textarea {
  border: 1px solid var(--ns-border);
  border-radius: 5px;
  background: var(--ns-bg);
  color: var(--ns-text);
  font: inherit;
  font-size: 11px;
}

.soar-flow-inspector input,
.soar-flow-inspector select {
  min-height: 30px;
  padding: 5px 8px;
}

.soar-flow-inspector textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 8px;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 10px;
  line-height: 1.45;
}

.soar-flow-catalog-toolbar,
.soar-flow-inspector-section span,
.soar-flow-own-issues-title {
  color: var(--ns-text-2);
  font-size: 10px;
}

.soar-flow-catalog-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: -4px 0 9px;
}
.soar-flow-option { display: flex; flex-direction: column; gap: 2px; line-height: 1.25; }
.soar-flow-option small, .soar-flow-action-description { color: var(--ns-text-3); font-size: 9px; }
.soar-flow-catalog-warning { display: block; margin-top: 3px; color: var(--ns-warning); font-size: 9px; line-height: 1.35; }
.soar-flow-action-description { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.soar-flow-parameter-form { display: grid; gap: 7px; margin-top: 6px; }
.soar-flow-parameter-form label { margin: 0; }
.soar-flow-parameter-form label > span { display: block; margin-bottom: 3px; color: var(--ns-text-2); font-size: 10px; }
.soar-flow-parameter-form label > span i { margin-left: 2px; color: var(--ns-danger); font-style: normal; }
.soar-flow-parameter-form small { display: block; margin-top: 2px; color: var(--ns-text-3); font-size: 9px; line-height: 1.35; }
.soar-flow-advanced-details { margin-top: 8px; }
.soar-flow-advanced-details summary { color: var(--ns-text-3); cursor: pointer; font-size: 10px; }

.soar-flow-warn-line {
  margin: -4px 0 9px;
  color: var(--ns-warning);
  font-size: 10px;
}

.soar-flow-inspector-section {
  margin-top: 12px;
  color: var(--ns-text-2);
  font-size: 10px;
}

.soar-flow-inspector-section textarea {
  margin: 5px 0;
}

.soar-flow-retry-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 7px;
  align-items: center;
  margin-top: 8px;
}

.soar-flow-retry-grid label {
  margin-bottom: 0;
}

.soar-flow-retry-grid > .el-button {
  grid-column: 1 / -1;
}

.soar-flow-switch-case-row {
  display: grid;
  grid-template-columns: 1fr 1fr auto;
  gap: 5px;
  align-items: center;
  margin-top: 5px;
}

.soar-flow-switch-case-row input {
  width: 100%;
  box-sizing: border-box;
  min-height: 28px;
  padding: 4px 7px;
}

.soar-flow-switch-case-row .el-button {
  margin: 0;
}

.soar-flow-raw-json {
  max-height: 160px;
  margin: 5px 0 0;
  overflow: auto;
  padding: 7px;
  border: 1px solid var(--ns-border);
  border-radius: 4px;
  background: var(--ns-bg-inset);
  color: var(--ns-text-3);
  font-size: 9px;
  white-space: pre-wrap;
}

.soar-flow-own-issues {
  margin-top: 12px;
}

.soar-flow-issue-row {
  margin: 0 -2px 6px;
  padding: 5px 7px;
  border-left: 3px solid var(--ns-danger);
  background: color-mix(in srgb, var(--ns-danger) 7%, transparent);
  font-size: 10px;
}

.soar-flow-issue-row.warning {
  border-left-color: var(--ns-warning);
  background: color-mix(in srgb, var(--ns-warning) 8%, transparent);
}

.soar-flow-issue-row b,
.soar-flow-issue-row span {
  margin-right: 4px;
}

.soar-flow-issue-row span {
  color: var(--ns-text-3);
}

.soar-flow-issue-row p {
  margin: 3px 0 0;
  color: var(--ns-text-2);
}

.soar-flow-empty {
  color: var(--ns-text-3);
  font-size: 11px;
  line-height: 1.5;
}
.soar-flow-common-field { margin-top: 8px; }
.soar-flow-common-field small { display: block; margin-top: 4px; color: var(--ns-text-3); font-size: 10px; line-height: 1.4; }
.soar-flow-common-field :deep(.el-select) { width: 100%; margin-top: 3px; }
.soar-flow-section-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; }
.manual-task-field-row { display: grid; grid-template-columns: minmax(0, 1.2fr) 90px auto auto; gap: 5px; align-items: center; margin-bottom: 6px; }
.manual-task-field-row input, .manual-task-field-row select { min-width: 0; width: 100%; box-sizing: border-box; }
.manual-task-required { display: flex !important; align-items: center; gap: 3px; margin: 0 !important; white-space: nowrap; font-size: 10px !important; }
.manual-task-required input { width: auto; margin: 0; }
@media (max-width: 520px) { .manual-task-field-row { grid-template-columns: 1fr 1fr auto; }.manual-task-required { grid-column: 1 / 3; } }
</style>
