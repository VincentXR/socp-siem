<script setup lang="ts">
import { computed } from 'vue'
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import type { PortSpec, FlowNodeData } from './types'
import { nodeTypeMeta } from './nodeRegistry'
import { runStatusTone } from './runHighlight'
import { useI18n } from '../../../composables/useI18n'

const props = defineProps<NodeProps<FlowNodeData>>()

const { t } = useI18n()

const raw = computed(() => props.data?.raw)
const meta = computed(() => nodeTypeMeta(props.data?.nodeType ?? ''))
const title = computed(() => {
  const name = raw.value?.name
  return typeof name === 'string' && name.trim() ? name : String(raw.value?.id ?? '')
})
const typeLabel = computed(() => {
  const nodeType = String(props.data?.nodeType ?? '')
  const labelKey = meta.value?.labelKey
  if (!labelKey) return nodeType
  const translated = t(labelKey)
  return translated === labelKey ? (meta.value?.label ?? nodeType) : translated
})

const acceptsTarget = computed(() => Boolean(props.data?.acceptsTarget))

/** Run-path highlight (Slice 4): present only for recognised statuses. */
const runStatus = computed(() => {
  const status = props.data?.runStatus
  return status && runStatusTone(status) ? status : undefined
})
const runTone = computed(() => (runStatus.value ? runStatusTone(runStatus.value) : undefined))
const runChipTitle = computed(() => {
  if (!runStatus.value) return ''
  const count = props.data?.runIterations ?? 1
  const paths = (props.data?.runIterationPaths ?? []).filter(path => Boolean(path))
  const base = t('soarV2.runHighlightNodeTitle', { count })
  return paths.length ? `${base}: ${paths.join(', ')}` : base
})

/** Secondary line under the title (mirrors the old node card). */
const summary = computed(() => {
  const node = raw.value
  if (!node) return ''
  if (props.data?.unsupported) return t('soar.unsupportedNode')
  const type = props.data?.nodeType
  if (type === 'ACTION') return String(node.actionRef ?? '')
  if (type === 'CONDITION') {
    const expression = node.expression
    return typeof expression === 'string' ? expression : ''
  }
  if (type === 'SWITCH') {
    const expression = node.expression
    return typeof expression === 'string' ? expression : ''
  }
  if (type === 'END') return String(node.outcome ?? '')
  if (type === 'APPROVAL') {
    const config = (node.config && typeof node.config === 'object' ? node.config : {}) as Record<string, unknown>
    return config.timeoutSeconds ? `timeout ${config.timeoutSeconds}s` : ''
  }
  return String(node.id ?? '')
})

const errorCount = computed(() => props.data?.errors ?? 0)
const warningCount = computed(() => props.data?.warnings ?? 0)
const issueTotal = computed(() => errorCount.value + warningCount.value)
const hasWarningsOnly = computed(() => errorCount.value === 0 && warningCount.value > 0)

const sourcePorts = computed<PortSpec[]>(() => props.data?.sourcePorts ?? [])
const handleConnectable = computed(() => {
  if (props.data?.unsupported) return false
  return Boolean(props.connectable)
})
const targetConnectable = computed(() => {
  if (props.data?.unsupported) return false
  return Boolean(props.connectable)
})

function handleTop(index: number, count: number): string {
  return `${((index + 1) / (count + 1)) * 100}%`
}

function portHandleId(port: PortSpec): string {
  return port.token === '' ? 'default' : port.token
}

function portTitle(port: PortSpec): string {
  const translated = t(port.labelKey)
  return translated === port.labelKey ? (port.label || t('soar.port.default')) : translated
}

function runStatusLabel(status: string): string {
  const key = 'soarV2.status.' + status
  const translated = t(key)
  return translated === key ? status : translated
}
</script>

<template>
  <div
    class="soar-flow-node-card"
    :class="[
      `tone-${props.data?.tone ?? 'action'}`,
      { selected: props.selected, unsupported: props.data?.unsupported },
      errorCount ? 'vf-invalid-node' : hasWarningsOnly ? 'vf-warn-node' : '',
      runTone ? `run-${runTone}` : '',
    ]"
    :title="props.id"
  >
    <Handle
      v-if="acceptsTarget"
      id="in"
      type="target"
      :position="Position.Left"
      class="soar-handle soar-handle-target"
      :connectable="targetConnectable"
    />

    <Handle
      v-for="(port, index) in sourcePorts"
      :id="portHandleId(port)"
      :key="portHandleId(port)"
      type="source"
      :position="Position.Right"
      class="soar-handle soar-handle-source"
      :connectable="handleConnectable"
      :style="{ top: handleTop(index, sourcePorts.length) }"
      :title="portTitle(port)"
    />

    <span class="soar-flow-node-type">
      {{ typeLabel }}
      <span
        v-if="runStatus"
        class="soar-flow-node-run-chip"
        :title="runChipTitle"
      >{{ runStatusLabel(runStatus) }}</span>
    </span>
    <span v-if="issueTotal" class="soar-flow-node-issue-badge" :class="{ warning: hasWarningsOnly }">{{ issueTotal }}</span>
    <strong>{{ title }}</strong>
    <small v-if="summary">{{ summary }}</small>
  </div>
</template>

<style scoped>
.soar-flow-node-card {
  position: relative;
  display: flex;
  width: 200px;
  min-height: 66px;
  box-sizing: border-box;
  flex-direction: column;
  gap: 3px;
  padding: 9px 12px;
  border: 1px solid var(--soar-node-color, var(--ns-border));
  border-left: 4px solid var(--soar-node-color, var(--ns-accent));
  border-radius: 7px;
  background: var(--ns-bg);
  color: var(--ns-text);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.09);
  font-size: 12px;
  text-align: left;
  transition: box-shadow 120ms ease, border-color 120ms ease;
}

.soar-flow-node-card:hover,
.soar-flow-node-card.selected {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--soar-node-color, var(--ns-accent)) 28%, transparent), 0 4px 12px rgba(15, 23, 42, 0.14);
}

.soar-flow-node-card.vf-invalid-node {
  box-shadow: 0 0 0 2px var(--ns-danger);
}
.soar-flow-node-card.vf-warn-node {
  box-shadow: 0 0 0 2px var(--ns-warning);
}
.soar-flow-node-card.unsupported {
  opacity: 0.92;
  border-style: dashed;
}

/* Run-path highlight ring (Slice 4). Declared after the validation/hover rings
   so an executed node keeps its run color; nodes without a highlight are
   untouched and keep the current look. */
.soar-flow-node-card.run-succeeded { --run-status-color: var(--ns-success); }
.soar-flow-node-card.run-failed { --run-status-color: var(--ns-danger); }
.soar-flow-node-card.run-unknown { --run-status-color: var(--ns-warning); }
.soar-flow-node-card.run-timeout { --run-status-color: #ea580c; }
.soar-flow-node-card.run-running { --run-status-color: var(--ns-accent); }
.soar-flow-node-card.run-waiting { --run-status-color: #d97706; }
.soar-flow-node-card.run-cancelled { --run-status-color: var(--ns-info); }
.soar-flow-node-card.run-suppressed { --run-status-color: var(--ns-text-3); }

.soar-flow-node-card.run-succeeded,
.soar-flow-node-card.run-failed,
.soar-flow-node-card.run-unknown,
.soar-flow-node-card.run-timeout,
.soar-flow-node-card.run-running,
.soar-flow-node-card.run-waiting,
.soar-flow-node-card.run-cancelled,
.soar-flow-node-card.run-suppressed {
  border-left-color: var(--run-status-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--run-status-color) 55%, transparent);
}

.soar-flow-node-type {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--soar-node-color, var(--ns-text-3));
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.soar-flow-node-card strong {
  font-size: 12px;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.soar-flow-node-card small {
  overflow: hidden;
  color: var(--ns-text-3);
  font-size: 10px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soar-flow-node-issue-badge {
  position: absolute;
  top: -7px;
  right: -7px;
  z-index: 2;
  min-width: 16px;
  height: 16px;
  box-sizing: border-box;
  padding: 0 4px;
  border-radius: 9px;
  background: var(--ns-danger);
  color: #fff;
  font-size: 9px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
}
.soar-flow-node-issue-badge.warning {
  background: var(--ns-warning);
}

.soar-flow-node-run-chip {
  margin-left: auto;
  max-width: 110px;
  box-sizing: border-box;
  overflow: hidden;
  padding: 0 5px;
  border-radius: 7px;
  background: color-mix(in srgb, var(--run-status-color, var(--ns-accent)) 14%, transparent);
  color: var(--run-status-color, var(--ns-accent));
  font-size: 8px;
  font-weight: 700;
  line-height: 15px;
  letter-spacing: 0.02em;
  text-overflow: ellipsis;
  text-transform: none;
  white-space: nowrap;
}
</style>

<style>
/* Handle anchors sit on the card border; target on the left, sources on the right. */
.soar-flow-node-card .soar-handle {
  width: 10px;
  height: 10px;
  border: 2px solid var(--ns-bg);
  background: var(--soar-node-color, var(--ns-accent));
}
.soar-flow-node-card .soar-handle-target {
  left: -6px;
}
.soar-flow-node-card .soar-handle-source {
  right: -6px;
}

/* Tone -> accent colour language (design doc §visual tones): the tone-* class on
   any node card / palette item sets --soar-node-color, consumed by the card border,
   type label, handles and palette dots. Kept unscoped so palette items (a sibling
   component) inherit the same per-tone colours. Run-path highlight classes override
   the accent via their own --run-status-color and are unaffected. */
.tone-start { --soar-node-color: #2563eb; }
.tone-end { --soar-node-color: #64748b; }
.tone-action { --soar-node-color: #0891b2; }
.tone-logic { --soar-node-color: #7c3aed; }
.tone-control { --soar-node-color: #ea580c; }
.tone-wait { --soar-node-color: #ca8a04; }
.tone-human { --soar-node-color: #db2777; }
.tone-data { --soar-node-color: #059669; }
</style>
