<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import 'element-plus/es/components/button/style/css.mjs'
import 'element-plus/es/components/tag/style/css.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import ElTag from 'element-plus/es/components/tag/index.mjs'
import { listV2Actions, listV2Connections, type SoarV2ActionDescriptor, type SoarV2Connection } from '../../../api'
import { NODE_TYPE_ORDER, SOAR_NODE_REGISTRY } from './nodeRegistry'
import { rawNodeType, isUnsupportedNodeType, type SoarFlowApi } from './useDefinitionFlow'
import type { EditorNode, ValidationIssue } from './types'
import { useI18n } from '../../../composables/useI18n'

const props = defineProps<{ flow: SoarFlowApi; node: EditorNode | null }>()

const { t } = useI18n()

const END_OUTCOMES = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'SUPPRESSED', 'FAILED', 'TIMED_OUT', 'CANCELLED']

const nodeType = computed(() => (props.node ? rawNodeType(props.node) : ''))
const unsupported = computed(() => Boolean(props.node) && isUnsupportedNodeType(props.node!))

const typeOptions = computed(() => NODE_TYPE_ORDER.map(type => SOAR_NODE_REGISTRY[type]))

/* ---------- ACTION catalog (loaded lazily) ---------- */
const actions = ref<SoarV2ActionDescriptor[]>([])
const connections = ref<SoarV2Connection[]>([])
const actionCatalogState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')

async function loadActionCatalog(): Promise<void> {
  if (actionCatalogState.value === 'loading') return
  actionCatalogState.value = 'loading'
  try {
    const [actionResult, connectionResult] = await Promise.allSettled([
      listV2Actions(),
      listV2Connections(0, 100),
    ])
    if (actionResult.status === 'fulfilled') actions.value = actionResult.value
    if (connectionResult.status === 'fulfilled') connections.value = connectionResult.value.items
    actionCatalogState.value = 'loaded'
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
  applyJsonInto('parameters', parametersText.value)
  props.flow.touchAfterNodeEdit()
}

function commitTarget(): void {
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
  const node = props.node
  if (!node) return
  const parsed = Number(raw)
  if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed < min || parsed > max) return
  const current = node.retry && typeof node.retry === 'object' ? node.retry as Record<string, unknown> : {}
  node.retry = { ...current, [field]: parsed }
  props.flow.touchAfterNodeEdit()
}

function addRetry(): void {
  const node = props.node
  if (!node) return
  node.retry = { maxAttempts: 3, backoffSeconds: 30 }
  props.flow.touchAfterNodeEdit()
}

function removeRetry(): void {
  const node = props.node
  if (!node) return
  delete node.retry
  props.flow.touchAfterNodeEdit()
}

/* ---------- type change / removal ---------- */
function onTypeChange(event: Event): void {
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

const selectedAction = computed<SoarV2ActionDescriptor | undefined>(() => {
  const ref = props.node ? String(props.node.actionRef ?? '') : ''
  return ref ? actions.value.find(action => action.actionRef === ref) : undefined
})

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
</script>

<template>
  <aside class="soar-flow-inspector" aria-label="Node properties">
    <div class="soar-v2-panel-title">{{ t('soar.propertyPanelTitle') }}</div>

    <template v-if="node">
      <div class="soar-flow-inspector-head">
        <div>
          <span class="soar-flow-id-label">ID</span>
          <code class="soar-flow-node-id">{{ node.id }}</code>
        </div>
        <el-button size="small" type="danger" plain :disabled="nodeType === 'START'" @click="flow.removeNode(node.id)">{{ t('common.delete') }}</el-button>
      </div>

      <!-- Unsupported / read-only banner -->
      <div v-if="unsupported" class="soar-flow-readonly-banner">
        <b>{{ t('soar.unsupportedNode') }}</b>
        <p>{{ t('soar.readOnlyHint') }}</p>
      </div>

      <label>
        Type
        <select :value="nodeType" :disabled="unsupported" @change="onTypeChange">
          <option v-for="option in typeOptions" :key="option.type" :value="option.type" :disabled="option.comingSoon">
            {{ option.type }}
          </option>
        </select>
      </label>

      <label v-if="!unsupported">
        Name
        <input :value="scalar(node, 'name')" placeholder="node name" @input="updateScalar('name', ($event.target as HTMLInputElement).value)" />
      </label>

      <!-- START -->
      <div v-if="nodeType === 'START'" class="soar-flow-hint">
        {{ t('soar.startHint') }}
      </div>

      <!-- ACTION -->
      <template v-if="nodeType === 'ACTION'">
        <label>
          Action ref
          <input
            :value="scalar(node, 'actionRef')"
            :list="'soar-action-refs-' + node.id"
            placeholder="socp.alert/get@1"
            @input="updateScalar('actionRef', ($event.target as HTMLInputElement).value)"
          />
          <datalist :id="'soar-action-refs-' + node.id">
            <option v-for="action in actions" :key="action.actionRef" :value="action.actionRef">
              {{ action.displayName }} · {{ action.riskLevel }}
            </option>
          </datalist>
        </label>
        <div class="soar-flow-catalog-toolbar">
          <el-tag v-if="!actionRefKnown && scalar(node, 'actionRef')" size="small" type="warning">unregistered ref</el-tag>
          <el-tag v-else-if="selectedAction" size="small" :type="riskTag(selectedAction.riskLevel)">{{ selectedAction.riskLevel }}</el-tag>
          <el-button size="small" :loading="actionCatalogState === 'loading'" @click="loadActionCatalog">{{ t('common.refresh') }}</el-button>
        </div>

        <label>
          Connection ref
          <input
            :value="scalar(node, 'connectionRef')"
            :list="'soar-connection-refs-' + node.id"
            placeholder="optional connector id"
            @input="updateScalar('connectionRef', ($event.target as HTMLInputElement).value)"
          />
          <datalist :id="'soar-connection-refs-' + node.id">
            <option v-for="connection in connections" :key="connection.id" :value="connection.id">{{ connection.name }}</option>
          </datalist>
        </label>
        <div v-if="scalar(node, 'connectionRef') && !connectionRefKnown()" class="soar-flow-warn-line">connection not in catalog</div>

        <div class="soar-flow-inspector-section">
          <span>parameters</span>
          <textarea v-model="parametersText" rows="4" spellcheck="false" />
          <el-button size="small" @click="commitParameters">Apply</el-button>
        </div>
        <div class="soar-flow-inspector-section">
          <span>target</span>
          <textarea v-model="targetText" rows="4" spellcheck="false" />
          <el-button size="small" @click="commitTarget">Apply</el-button>
        </div>

        <div v-if="hasRetry()" class="soar-flow-retry-grid">
          <label>maxAttempts<input type="number" min="1" max="10" :value="String(retryConfig?.maxAttempts ?? 1)" @input="updateRetry('maxAttempts', ($event.target as HTMLInputElement).value, 1, 10)" /></label>
          <label>backoffSeconds<input type="number" min="0" max="300" :value="String(retryConfig?.backoffSeconds ?? 0)" @input="updateRetry('backoffSeconds', ($event.target as HTMLInputElement).value, 0, 300)" /></label>
          <el-button size="small" plain @click="removeRetry">Remove retry</el-button>
        </div>
        <el-button v-else size="small" plain @click="addRetry">Add retry</el-button>
      </template>

      <!-- CONDITION -->
      <label v-if="nodeType === 'CONDITION'">
        Expression
        <input :value="scalar(node, 'expression')" placeholder="trigger.severity == 'HIGH'" @input="updateScalar('expression', ($event.target as HTMLInputElement).value)" />
      </label>

      <!-- END -->
      <label v-if="nodeType === 'END'">
        Outcome
        <select :value="scalar(node, 'outcome') || 'SUCCEEDED'" @change="updateScalar('outcome', ($event.target as HTMLSelectElement).value)">
          <option v-for="outcome in END_OUTCOMES" :key="outcome" :value="outcome">{{ outcome }}</option>
        </select>
      </label>

      <!-- APPROVAL -->
      <template v-if="nodeType === 'APPROVAL'">
        <div class="soar-flow-inspector-section">
          <span>Approval policy (config)</span>
          <label>timeoutSeconds
            <input type="number" min="0" :max="7 * 24 * 3600" :value="approvalNumberValue('timeoutSeconds', 86400)" @input="updateApprovalConfigNumber('timeoutSeconds', ($event.target as HTMLInputElement).value, 7 * 24 * 3600)" />
          </label>
          <label>requiredApprovals
            <input type="number" min="1" :value="approvalNumberValue('requiredApprovals', 1)" @input="updateApprovalConfigNumber('requiredApprovals', ($event.target as HTMLInputElement).value)" />
          </label>
          <label>allowedRoles<input :value="approvalTagsValue('allowedRoles')" placeholder="comma separated" @input="updateApprovalTags('allowedRoles', ($event.target as HTMLInputElement).value)" /></label>
          <label>allowedGroups<input :value="approvalTagsValue('allowedGroups')" placeholder="comma separated" @input="updateApprovalTags('allowedGroups', ($event.target as HTMLInputElement).value)" /></label>
          <label>approverRoles<input :value="approvalTagsValue('approverRoles')" placeholder="comma separated" @input="updateApprovalTags('approverRoles', ($event.target as HTMLInputElement).value)" /></label>
          <label>approverGroups<input :value="approvalTagsValue('approverGroups')" placeholder="comma separated" @input="updateApprovalTags('approverGroups', ($event.target as HTMLInputElement).value)" /></label>
        </div>
      </template>

      <!-- FOREACH -->
      <template v-if="nodeType === 'FOREACH'">
        <label>
          itemsPath
          <input :value="nestedTextValue('config', 'itemsPath')" placeholder="vars.items" @input="updateNested('config', 'itemsPath', ($event.target as HTMLInputElement).value)" />
        </label>
        <label>
          itemVariable
          <input :value="nestedTextValue('config', 'itemVariable')" placeholder="vars.item" @input="updateNested('config', 'itemVariable', ($event.target as HTMLInputElement).value)" />
        </label>
        <div class="soar-flow-retry-grid">
          <label>concurrency<input type="number" min="1" max="10" :value="nestedNumberValue('limits', 'concurrency', 1)" @input="updateNestedNumber('limits', 'concurrency', ($event.target as HTMLInputElement).value, 1, 10)" /></label>
          <label>maxItems<input type="number" min="1" max="100" :value="nestedNumberValue('limits', 'maxItems', 100)" @input="updateNestedNumber('limits', 'maxItems', ($event.target as HTMLInputElement).value, 1, 100)" /></label>
        </div>
      </template>

      <!-- PARALLEL -->
      <label v-if="nodeType === 'PARALLEL'">
        maxParallelism
        <input type="number" min="1" max="10" :value="nestedNumberValue('limits', 'maxParallelism', 2)" @input="updateNestedNumber('limits', 'maxParallelism', ($event.target as HTMLInputElement).value, 1, 10)" />
      </label>

      <!-- JOIN -->
      <label v-if="nodeType === 'JOIN'">
        strategy
        <select :value="scalar(node, 'strategy') || 'ALL_SUCCESS'" @change="updateScalar('strategy', ($event.target as HTMLSelectElement).value)">
          <option v-for="strategy in ['ALL_SUCCESS', 'ALL_DONE', 'ANY_SUCCESS']" :key="strategy" :value="strategy">{{ strategy }}</option>
        </select>
      </label>

      <!-- SUB_PLAYBOOK -->
      <label v-if="nodeType === 'SUB_PLAYBOOK'">
        playbookVersionId
        <input :value="scalar(node, 'playbookVersionId')" placeholder="published version id" @input="updateScalar('playbookVersionId', ($event.target as HTMLInputElement).value)" />
      </label>

      <!-- DELAY -->
      <label v-if="nodeType === 'DELAY'">
        durationSeconds
        <input type="number" min="1" max="86400" :value="nestedNumberValue('config', 'durationSeconds', 60)" @input="updateNestedNumber('config', 'durationSeconds', ($event.target as HTMLInputElement).value, 1, 86400)" />
      </label>

      <!-- SET_VARIABLE -->
      <template v-if="nodeType === 'SET_VARIABLE'">
        <label>
          name (vars.*)
          <input :value="nestedTextValue('config', 'name')" placeholder="vars.note" @input="updateNested('config', 'name', ($event.target as HTMLInputElement).value)" />
        </label>
        <label>
          value
          <input :value="nestedTextValue('config', 'value')" placeholder="auto" @input="updateNested('config', 'value', ($event.target as HTMLInputElement).value)" />
        </label>
      </template>

      <!-- MANUAL_TASK -->
      <template v-if="nodeType === 'MANUAL_TASK'">
        <label>
          timeoutSeconds
          <input type="number" min="0" :max="7 * 24 * 3600" :value="nestedNumberValue('config', 'timeoutSeconds', 86400)" @input="updateNestedNumber('config', 'timeoutSeconds', ($event.target as HTMLInputElement).value, 0, 7 * 24 * 3600)" />
        </label>
        <label>
          assignee
          <input :value="nestedTextValue('config', 'assignee')" placeholder="analyst handle" @input="updateNested('config', 'assignee', ($event.target as HTMLInputElement).value)" />
        </label>
        <div class="soar-flow-hint">formSchema is edited via the Advanced node JSON below</div>
      </template>

      <!-- Generic secondary fields for read-only types (raw display only) -->
      <template v-if="unsupported">
        <div class="soar-flow-inspector-section">
          <span>raw fields</span>
          <pre class="soar-flow-raw-json">{{ stringifyJson(node) }}</pre>
        </div>
      </template>

      <!-- Advanced node JSON -->
      <div class="soar-flow-inspector-section">
        <span>Advanced node JSON</span>
        <textarea v-model="nodeConfigText" rows="8" spellcheck="false" @focus="showAdvanced" />
        <el-button size="small" @click="applyNodeConfigJson">Apply node JSON</el-button>
      </div>

      <!-- Selected node validation issues -->
      <div v-if="ownIssues.length" class="soar-flow-own-issues">
        <span class="soar-flow-own-issues-title">Validation issues</span>
        <div v-for="(issue, index) in ownIssues" :key="`${issue.code}-${index}`" class="soar-flow-issue-row" :class="{ warning: issue.severity === 'WARNING' }">
          <b>{{ issue.code || 'ISSUE' }}</b><span>{{ issue.path || '' }}</span><p>{{ issue.message }}</p>
        </div>
      </div>
    </template>

    <div v-else class="soar-flow-empty">Select a node to inspect its contract.</div>
  </aside>
</template>

<style scoped>
.soar-flow-inspector {
  min-width: 0;
  padding: 10px;
  border-left: 1px solid var(--ns-border);
  background: var(--ns-bg-subtle);
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
</style>
